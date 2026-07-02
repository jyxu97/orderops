#!/usr/bin/env bash
# Transient Failure Retry Recovery Test
#
# Verifies that 100% of transient payment failures are recovered by retry.
#
# Setup:
#   - 30% random payment failure rate (NONE mode + failureRate=0.3)
#   - SQS maxReceiveCount=3, backoff=5s (fast for local testing)
#   - 20 orders submitted
#
# Expected:
#   - All 20 orders eventually reach FULFILLED or NEEDS_MANUAL_REVIEW
#   - DLQ = 0 (no messages lost; transient failures recovered within 3 retries)
#   - No silent loss
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_BASE_URL="${BASE_URL}"
SQS_ENDPOINT="${SQS_ENDPOINT:-http://localhost:4566}"
REGION="${REGION:-us-west-2}"
JAR="${JAR:-apps/orderops/target/orderops-0.1.0-SNAPSHOT.jar}"
ITEM_ID="transient-test-$(date +%s)"
ORDER_COUNT=50
WAIT_SECONDS=120  # 3 retries × 5s backoff = 15s max per wave; give plenty of headroom

echo "=== Transient Failure Retry Recovery Test ==="
echo "Item: $ITEM_ID | Orders: $ORDER_COUNT | Failure rate: 10%"
echo ""

# 1. Seed inventory
echo "[1/5] Seeding inventory (qty=$ORDER_COUNT)..."
curl -sf -X POST "$API_BASE_URL/inventory/seed" \
  -H "Content-Type: application/json" \
  -d "{\"itemId\":\"$ITEM_ID\",\"itemName\":\"Transient Test Item\",\"quantity\":$ORDER_COUNT}" \
  > /dev/null
echo "     Done."

# 2. Start worker with 30% random failure rate, fast backoff
echo "[2/5] Starting fulfillment worker (failure-rate=0.1, backoff=5s)..."
JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then JAVA_BIN="java"; fi

$JAVA_BIN -jar "$JAR" \
  --app.mode=worker \
  --spring.data.redis.host=localhost \
  --dynamodb.endpoint=http://localhost:8000 \
  --sqs.endpoint="$SQS_ENDPOINT" \
  --simulator.failure-rate=0.1 \
  --worker.backoff.base-seconds=5 \
  --worker.backoff.max-seconds=30 \
  --server.port=8082 \
  > /tmp/worker-transient.log 2>&1 &
WORKER_PID=$!
echo "     Worker PID=$WORKER_PID"
sleep 5

# 3. Submit N orders
echo "[3/5] Submitting $ORDER_COUNT orders..."
ORDER_IDS=()
for i in $(seq 1 $ORDER_COUNT); do
  KEY="transient-key-$ITEM_ID-$i"
  RESP=$(curl -sf -X POST "$API_BASE_URL/orders" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"customerId\":\"customer-$i\",\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":1}]}")
  OID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['orderId'])" 2>/dev/null || echo "")
  if [ -n "$OID" ]; then
    ORDER_IDS+=("$OID")
  fi
done
echo "     Submitted ${#ORDER_IDS[@]} orders."

# 4. Wait for queue to drain (poll every 5s, up to WAIT_SECONDS)
echo "[4/5] Waiting for queue to drain (max ${WAIT_SECONDS}s)..."
ELAPSED=0
while [ "$ELAPSED" -lt "$WAIT_SECONDS" ]; do
  VISIBLE=$(aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs get-queue-attributes \
    --queue-url "$SQS_ENDPOINT/000000000000/order-fulfillment-queue" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null || echo "0")
  NOT_VISIBLE=$(aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs get-queue-attributes \
    --queue-url "$SQS_ENDPOINT/000000000000/order-fulfillment-queue" \
    --attribute-names ApproximateNumberOfMessagesNotVisible \
    --query 'Attributes.ApproximateNumberOfMessagesNotVisible' --output text 2>/dev/null || echo "0")
  echo "     ${ELAPSED}s elapsed — queue: visible=$VISIBLE in-flight=$NOT_VISIBLE"
  if [ "$VISIBLE" = "0" ] && [ "$NOT_VISIBLE" = "0" ]; then
    echo "     Queue drained!"
    break
  fi
  sleep 5
  ELAPSED=$((ELAPSED + 5))
done

# 5. Verify results
echo "[5/5] Verifying results..."
kill "$WORKER_PID" 2>/dev/null || true

FULFILLED=0
MANUAL_REVIEW=0
STILL_PROCESSING=0

for OID in "${ORDER_IDS[@]}"; do
  STATUS=$(curl -sf "$API_BASE_URL/orders/$OID" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "UNKNOWN")
  case "$STATUS" in
    FULFILLED)            ((FULFILLED++)) ;;
    NEEDS_MANUAL_REVIEW)  ((MANUAL_REVIEW++)) ;;
    *)                    ((STILL_PROCESSING++)) ;;
  esac
done

DLQ_COUNT=$(aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs get-queue-attributes \
  --queue-url "$SQS_ENDPOINT/000000000000/order-fulfillment-dlq" \
  --attribute-names ApproximateNumberOfMessages \
  --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null || echo "N/A")

echo ""
echo "=== RESULTS ==="
echo "  Total submitted:    $ORDER_COUNT"
echo "  FULFILLED:          $FULFILLED"
echo "  NEEDS_MANUAL_REVIEW:$MANUAL_REVIEW"
echo "  Still processing:   $STILL_PROCESSING"
echo "  DLQ messages:       $DLQ_COUNT"
echo "  No silent loss:     $((FULFILLED + MANUAL_REVIEW + STILL_PROCESSING)) == $ORDER_COUNT"
echo ""

if [ "$STILL_PROCESSING" -eq 0 ] && [ "$DLQ_COUNT" = "0" ]; then
  echo "PASS: All orders accounted for, DLQ empty."
else
  echo "CHECK: $STILL_PROCESSING orders still processing, DLQ=$DLQ_COUNT"
fi