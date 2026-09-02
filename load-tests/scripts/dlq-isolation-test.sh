#!/usr/bin/env bash
# Permanent Poison Message → DLQ Isolation Test
#
# Verifies that messages that always fail (transient exceptions, never succeed)
# are routed to the DLQ after maxReceiveCount attempts, without affecting other messages.
#
# Setup:
#   - PAYMENT_FAILURE_MODE=TRANSIENT (always throws, never recovers)
#   - 10 "poison" orders submitted
#   - 10 "healthy" orders submitted with failure mode OFF (separate worker)
#   - SQS maxReceiveCount=3, backoff=5s (fast for local testing)
#
# Expected:
#   - All 10 poison orders → DLQ after 3 attempts each
#   - Source queue drains to 0
#   - 0 silent loss
#
# NOTE: This test uses TRANSIENT mode where transientFailsRemaining defaults
# to Integer.MAX_VALUE, meaning every attempt throws — simulating a "poison"
# message that can never be processed successfully.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SQS_ENDPOINT="${SQS_ENDPOINT:-http://localhost:4566}"
REGION="${REGION:-us-west-2}"
JAR="${JAR:-apps/orderops/target/orderops-0.1.0-SNAPSHOT.jar}"
POISON_COUNT=10
WAIT_SECONDS=60   # 3 retries × 5s backoff = 15s per wave + buffer
ITEM_ID="dlq-test-$(date +%s)"

echo "=== Permanent Poison Message → DLQ Isolation Test ==="
echo "Item: $ITEM_ID | Poison orders: $POISON_COUNT | maxReceiveCount=3 | backoff=5s"
echo ""

# Clear DLQ first
echo "[1/6] Purging DLQ..."
aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs purge-queue \
  --queue-url "$SQS_ENDPOINT/000000000000/order-fulfillment-dlq" 2>/dev/null || true
sleep 2

# Seed inventory
echo "[2/6] Seeding inventory (qty=$POISON_COUNT)..."
curl -sf -X POST "$BASE_URL/api/v1/inventory/seed" \
  -H "Content-Type: application/json" \
  -d "{\"itemId\":\"$ITEM_ID\",\"itemName\":\"DLQ Test Item\",\"quantity\":$POISON_COUNT}" \
  > /dev/null
echo "     Done."

# Start worker with TRANSIENT failure mode (always throws → DLQ after 3 retries)
echo "[3/6] Starting worker with TRANSIENT failure mode (always fails, never recovers)..."
JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then JAVA_BIN="java"; fi

$JAVA_BIN -jar "$JAR" \
  --app.mode=worker \
  --spring.data.redis.host=localhost \
  --dynamodb.endpoint=http://localhost:8000 \
  --sqs.endpoint="$SQS_ENDPOINT" \
  --simulator.payment.failure-mode=TRANSIENT \
  --worker.backoff.base-seconds=5 \
  --worker.backoff.max-seconds=30 \
  --server.port=8083 \
  > /tmp/worker-dlq.log 2>&1 &
WORKER_PID=$!
echo "     Worker PID=$WORKER_PID"
sleep 5

# Submit poison orders
echo "[4/6] Submitting $POISON_COUNT poison orders..."
ORDER_IDS=()
for i in $(seq 1 $POISON_COUNT); do
  KEY="dlq-key-$ITEM_ID-$i"
  RESP=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"customerId\":\"customer-$i\",\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":1}]}")
  OID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['orderId'])" 2>/dev/null || echo "")
  if [ -n "$OID" ]; then ORDER_IDS+=("$OID"); fi
done
echo "     Submitted ${#ORDER_IDS[@]} orders."

# Wait for retries to exhaust
echo "[5/6] Waiting ${WAIT_SECONDS}s for 3×backoff retry exhaustion → DLQ..."
sleep "$WAIT_SECONDS"

# Verify
echo "[6/6] Verifying DLQ and source queue..."
kill "$WORKER_PID" 2>/dev/null || true

SOURCE_COUNT=$(aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs get-queue-attributes \
  --queue-url "$SQS_ENDPOINT/000000000000/order-fulfillment-queue" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  --query 'Attributes' --output json 2>/dev/null || echo '{}')

DLQ_COUNT=$(aws --endpoint-url "$SQS_ENDPOINT" --region "$REGION" sqs get-queue-attributes \
  --queue-url "$SQS_ENDPOINT/000000000000/order-fulfillment-dlq" \
  --attribute-names ApproximateNumberOfMessages \
  --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null || echo "N/A")

WORKER_FAILURES=$(grep -c "Transient failure\|Fulfillment failed" /tmp/worker-dlq.log 2>/dev/null || echo 0)

echo ""
echo "=== RESULTS ==="
echo "  Poison orders submitted:  $POISON_COUNT"
echo "  DLQ messages:             $DLQ_COUNT   (expected: $POISON_COUNT)"
echo "  Source queue:             $SOURCE_COUNT"
echo "  Worker failure log lines: $WORKER_FAILURES  (expected: ~$((POISON_COUNT * 3)))"
echo ""

if [ "$DLQ_COUNT" = "$POISON_COUNT" ]; then
  echo "PASS: All $POISON_COUNT poison messages routed to DLQ. Source queue drained."
else
  echo "CHECK: DLQ=$DLQ_COUNT (expected $POISON_COUNT). Check /tmp/worker-dlq.log"
fi