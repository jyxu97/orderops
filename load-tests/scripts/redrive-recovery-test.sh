#!/usr/bin/env bash
# Dead-letter redrive recovery test.
#
# Proves the claim that dead-lettered work is recoverable, not merely quarantined:
#
#   1. A worker throwing transient faults exhausts each message's receive count
#   2. Those messages land in the DLQ, and their orders are left parked mid-flight
#      (PAYMENT_PROCESSING) rather than in a terminal state
#   3. POST /ops/dlq/redrive moves them back while a healthy worker is running
#   4. The parked orders resume from where they stopped and reach FULFILLED
#
# Step 2 is the part worth asserting: a *permanently* failing order never reaches the DLQ at
# all (the worker records the failure, routes it to manual review and deletes the message), so
# everything in the DLQ is interrupted work that resuming can actually complete.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SQS_ENDPOINT="${SQS_ENDPOINT:-http://localhost:4566}"
REGION="${AWS_REGION:-us-west-2}"
JAR="${JAR:-apps/orderops/target/orderops-0.1.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-java}"
ORDER_COUNT="${ORDER_COUNT:-5}"
ITEM_ID="redrive-test-$(date +%s)"

QUEUE_URL="$SQS_ENDPOINT/000000000000/order-fulfillment-queue"
DLQ_URL="$SQS_ENDPOINT/000000000000/order-fulfillment-dlq"

sqs_depth() {
  aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs get-queue-attributes \
    --queue-url "$1" --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null || echo 0
}

# Counts only the orders this run created. A global status query would also pick up orders
# parked by an earlier test, which makes the assertion unattributable — exactly the trap the
# queue purge above avoids for message counts.
count_own_in_status() {
  local wanted="$1" n=0
  for id in "${ORDER_IDS[@]}"; do
    local actual
    actual=$(curl -sf "$BASE_URL/api/v1/orders/$id" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo '?')
    [ "$actual" = "$wanted" ] && n=$((n + 1))
  done
  echo "$n"
}

cleanup() { pkill -f "server.port=8099" 2>/dev/null || true; }
trap cleanup EXIT

echo "=== Dead-Letter Redrive Recovery Test ==="
echo "Item: $ITEM_ID | Orders: $ORDER_COUNT"
echo

echo "[1/7] Purging both queues so the DLQ count is attributable..."
for q in "$QUEUE_URL" "$DLQ_URL"; do
  aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs purge-queue --queue-url "$q" 2>/dev/null || true
done
for _ in $(seq 1 30); do
  [ "$(sqs_depth "$QUEUE_URL")" = "0" ] && [ "$(sqs_depth "$DLQ_URL")" = "0" ] && break
  sleep 2
done

echo "[2/7] Seeding inventory..."
curl -sf -X POST "$BASE_URL/api/v1/inventory/seed" -H 'Content-Type: application/json' \
  -d "{\"itemId\":\"$ITEM_ID\",\"itemName\":\"Redrive Test\",\"quantity\":$ORDER_COUNT,\"unitPrice\":10.00}" > /dev/null

echo "[3/7] Submitting $ORDER_COUNT orders..."
ORDER_IDS=()
for _ in $(seq 1 "$ORDER_COUNT"); do
  id=$(curl -sf -X POST "$BASE_URL/api/v1/orders" -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"redrive-cust\",\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":1}]}" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['orderId'])")
  ORDER_IDS+=("$id")
done

echo "[4/7] Running a worker that always throws, until receives are exhausted..."
# Short backoff so maxReceiveCount=3 is reached in seconds instead of minutes.
APP_MODE=worker PAYMENT_FAILURE_MODE=TRANSIENT "$JAVA_BIN" -Dserver.port=8099 \
  -Dworker.backoff.base-seconds=1 -Dworker.backoff.max-seconds=2 \
  -jar "$JAR" > /tmp/redrive-failing-worker.log 2>&1 &
sleep 45
pkill -f "server.port=8099" 2>/dev/null || true
sleep 3

DLQ_BEFORE=$(sqs_depth "$DLQ_URL")
PARKED=$(count_own_in_status PAYMENT_PROCESSING)
echo "     DLQ: $DLQ_BEFORE | orders parked in PAYMENT_PROCESSING: $PARKED"

echo "[5/7] Starting a healthy worker..."
APP_MODE=worker "$JAVA_BIN" -Dserver.port=8099 -jar "$JAR" > /tmp/redrive-healthy-worker.log 2>&1 &
sleep 18

echo "[6/7] Redriving the dead-letter queue..."
REDRIVE=$(curl -sf -X POST "$BASE_URL/api/v1/ops/dlq/redrive")
echo "     $REDRIVE"
CONFLICT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/ops/dlq/redrive")
echo "     second request while running → HTTP $CONFLICT_CODE (expected 409)"
sleep 25

echo "[7/7] Verifying recovery..."
FULFILLED=$(count_own_in_status FULFILLED)
DLQ_AFTER=$(sqs_depth "$DLQ_URL")
FINAL=$(curl -sf "$BASE_URL/api/v1/ops/dlq/redrive")

echo
echo "=== RESULTS ==="
echo "  Orders submitted:          $ORDER_COUNT"
echo "  Dead-lettered:             $DLQ_BEFORE   (expected: $ORDER_COUNT)"
echo "  Parked mid-flight:         $PARKED   (expected: $ORDER_COUNT, and NOT terminal)"
echo "  Concurrent redrive:        HTTP $CONFLICT_CODE   (expected: 409)"
echo "  Recovered to FULFILLED:    $FULFILLED   (expected: $ORDER_COUNT)"
echo "  DLQ after redrive:         $DLQ_AFTER   (expected: 0)"
echo "  Final task:                $FINAL"
echo

if [ "$DLQ_BEFORE" = "$ORDER_COUNT" ] && [ "$PARKED" = "$ORDER_COUNT" ] \
   && [ "$FULFILLED" = "$ORDER_COUNT" ] && [ "$DLQ_AFTER" = "0" ] && [ "$CONFLICT_CODE" = "409" ]; then
  echo "PASS: dead-lettered work was recovered — $ORDER_COUNT interrupted orders resumed to FULFILLED."
  exit 0
fi
echo "CHECK: see /tmp/redrive-failing-worker.log and /tmp/redrive-healthy-worker.log"
exit 1
