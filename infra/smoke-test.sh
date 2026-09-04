#!/usr/bin/env bash
# Post-deployment smoke test.
#
# Exercises the paths that a broken deploy actually breaks, in the order a request would take
# them, and fails loudly rather than reporting a partial pass. Run against the ALB directly
# (BASE_URL) so a CloudFront cache cannot make a dead backend look alive.
#
# Deliberately narrow: this is a "did the deploy work" check, not a test suite. It creates one
# real order, which is the only way to know the DynamoDB transaction, the SQS publish and the
# WebSocket fan-out are all wired to the right resources.
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL is required, e.g. http://orderops-alb-xxx.us-west-2.elb.amazonaws.com}"
ITEM_ID="smoke-$(date +%s)"
CUSTOMER_ID="smoke-customer"
FAILURES=0

pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }

check_status() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then pass "$label ($actual)"; else fail "$label (got $actual, want $expected)"; fi
}

echo "=== OrderOps smoke test against $BASE_URL ==="
echo

echo "[1/6] Health"
check_status "GET /actuator/health/serving" 200 \
  "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE_URL/actuator/health/serving")"

echo "[2/6] Seeding a throwaway item"
# The JSON body is built into a variable rather than written inline inside the command
# substitution. Escaped quotes within a double-quoted "$( ... )" collapse: the braces then
# brace-expand into four separate requests and the result word-splits. Assigning first keeps
# one level of quoting and one request.
SEED_BODY="{\"itemId\":\"$ITEM_ID\",\"itemName\":\"Smoke Test\",\"quantity\":2,\"unitPrice\":1.00}"
SEED_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
  -X POST "$BASE_URL/api/v1/inventory/seed" \
  -H 'Content-Type: application/json' -d "$SEED_BODY")
check_status "POST /api/v1/inventory/seed" 201 "$SEED_CODE"

echo "[3/6] Creating an order (DynamoDB transaction + SQS publish)"
IDEMPOTENCY_KEY="smoke-$(date +%s)-$RANDOM"
CREATE_BODY="{\"customerId\":\"$CUSTOMER_ID\",\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":1}]}"
CREATE_RESPONSE=$(curl -s --max-time 15 -X POST "$BASE_URL/api/v1/orders" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEMPOTENCY_KEY" -d "$CREATE_BODY" || echo '{}')
ORDER_ID=$(echo "$CREATE_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('orderId',''))" 2>/dev/null || echo '')

if [ -n "$ORDER_ID" ]; then pass "order created ($ORDER_ID)"; else fail "order not created: $CREATE_RESPONSE"; fi

echo "[4/6] Idempotent replay returns the same order"
REPLAY_ID=$(curl -s --max-time 15 -X POST "$BASE_URL/api/v1/orders" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEMPOTENCY_KEY" -d "$CREATE_BODY" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('orderId',''))" 2>/dev/null || echo '')
if [ -n "$ORDER_ID" ] && [ "$REPLAY_ID" = "$ORDER_ID" ]; then
  pass "replay returned the original order"
else
  fail "replay returned '$REPLAY_ID', expected '$ORDER_ID'"
fi

echo "[5/6] Operations endpoints (SQS reachable from the API)"
check_status "GET /api/v1/ops/queue-health" 200 \
  "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE_URL/api/v1/ops/queue-health")"
QUEUE_AVAILABLE=$(curl -s --max-time 10 "$BASE_URL/api/v1/ops/queue-health" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('available'))" 2>/dev/null || echo 'error')
if [ "$QUEUE_AVAILABLE" = "True" ]; then
  pass "SQS readable from the API"
else
  # A deploy where the task role cannot read SQS still serves orders, so this would not show
  # up as an unhealthy target — it has to be checked explicitly.
  fail "SQS not readable (available=$QUEUE_AVAILABLE)"
fi

echo "[6/6] Async fulfillment progresses (worker is consuming)"
FINAL_STATUS="unknown"
if [ -n "$ORDER_ID" ]; then
  for _ in $(seq 1 20); do
    FINAL_STATUS=$(curl -s --max-time 10 "$BASE_URL/api/v1/orders/$ORDER_ID" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo '')
    case "$FINAL_STATUS" in
      FULFILLED|NEEDS_MANUAL_REVIEW) break ;;
    esac
    sleep 3
  done
fi
# Reaching a terminal state proves the worker task is alive and pointed at the same queue and
# table as the API. INVENTORY_RESERVED after a minute means the worker is not consuming.
if [ "$FINAL_STATUS" = "FULFILLED" ]; then
  pass "order reached FULFILLED"
elif [ "$FINAL_STATUS" = "NEEDS_MANUAL_REVIEW" ]; then
  pass "order reached a terminal state (NEEDS_MANUAL_REVIEW — worker is consuming)"
else
  fail "order stuck at '$FINAL_STATUS' — the worker may not be consuming"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "=== SMOKE TEST PASSED ==="
  exit 0
fi
echo "=== SMOKE TEST FAILED ($FAILURES check(s)) ==="
exit 1
