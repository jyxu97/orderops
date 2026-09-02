#!/bin/bash
# Idempotency Correctness Test
#
# Verifies that repeated checkout requests with the same Idempotency-Key
# always return the same orderId and never double-deduct inventory.
#
# Test design:
#   Phase 1: Create 10 orders with known keys → record orderId for each key
#   Phase 2: Send 100 duplicate requests (10 keys × 10 repeats each)
#   Phase 3: Verify every duplicate response matches the original orderId
#   Phase 4: Verify inventory was deducted exactly once per key (10 total)
#
# Expected:
#   - 100% duplicate requests return same orderId as original
#   - availableQuantity = initialQty - 10  (deducted exactly once per key)
#   - 0 silent duplicates (extra orders)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ITEM_ID="idem-test-$(date +%s)"
INITIAL_QTY=100
NUM_KEYS=10
REPEATS_PER_KEY=10

echo "=== Idempotency Correctness Test ==="
echo "Item: $ITEM_ID | Keys: $NUM_KEYS | Repeats/key: $REPEATS_PER_KEY"
echo ""

# 1. Seed inventory
echo "[1/4] Seeding inventory (qty=$INITIAL_QTY)..."
curl -sf -X POST "$BASE_URL/api/v1/inventory/seed" \
  -H "Content-Type: application/json" \
  -d "{\"itemId\":\"$ITEM_ID\",\"itemName\":\"Idem Test\",\"quantity\":$INITIAL_QTY}" > /dev/null
echo "     Done."

# Temp dir for storing order IDs by key index
TMPDIR_IDEM=$(mktemp -d /tmp/idem-test-XXXX)
trap "rm -rf $TMPDIR_IDEM" EXIT

# 2. Phase 1: Create original orders for each key
echo "[2/4] Phase 1: Creating $NUM_KEYS original orders..."
for i in $(seq 1 $NUM_KEYS); do
  KEY="idem-key-$ITEM_ID-$i"
  RESP=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"customerId\":\"cust-$i\",\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":1}]}")
  OID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['orderId'])" 2>/dev/null)
  echo "$OID" > "$TMPDIR_IDEM/key-$i"
  echo "     key-$i → orderId=${OID:0:8}..."
done

# 3. Phase 2: Send duplicate requests (same keys)
echo "[3/4] Phase 2: Sending $((NUM_KEYS * REPEATS_PER_KEY)) duplicate requests..."
MATCH=0
MISMATCH=0
ERRORS=0

for rep in $(seq 1 $REPEATS_PER_KEY); do
  for i in $(seq 1 $NUM_KEYS); do
    KEY="idem-key-$ITEM_ID-$i"
    EXPECTED=$(cat "$TMPDIR_IDEM/key-$i")
    RESP=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: $KEY" \
      -d "{\"customerId\":\"cust-$i\",\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":1}]}" 2>/dev/null || echo "ERROR")

    if [ "$RESP" = "ERROR" ]; then
      ERRORS=$((ERRORS + 1))
    else
      GOT=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['orderId'])" 2>/dev/null || echo "PARSE_ERR")
      if [ "$GOT" = "$EXPECTED" ]; then
        MATCH=$((MATCH + 1))
      else
        MISMATCH=$((MISMATCH + 1))
        echo "     MISMATCH: key-$i rep-$rep expected=${EXPECTED:0:8} got=${GOT:0:8}"
      fi
    fi
  done
done

# 4. Verify inventory deduction
echo "[4/4] Verifying inventory deduction..."
FINAL_AVAILABLE=$(curl -sf "$BASE_URL/api/v1/inventory/$ITEM_ID" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['availableQuantity'])")
EXPECTED_AVAILABLE=$((INITIAL_QTY - NUM_KEYS))

echo ""
echo "=== RESULTS ==="
echo "  Original orders created:    $NUM_KEYS"
echo "  Duplicate requests sent:    $((NUM_KEYS * REPEATS_PER_KEY))"
echo "  Matched original orderId:   $MATCH  (expected: $((NUM_KEYS * REPEATS_PER_KEY)))"
echo "  Mismatched (new orderId):   $MISMATCH  (expected: 0)"
echo "  Errors:                     $ERRORS   (expected: 0)"
echo "  Final availableQty:         $FINAL_AVAILABLE  (expected: $EXPECTED_AVAILABLE)"
echo "  Inventory correctness:      $([ "$FINAL_AVAILABLE" -eq "$EXPECTED_AVAILABLE" ] && echo 'PASS' || echo 'FAIL')"
echo ""

if [ "$MISMATCH" -eq 0 ] && [ "$ERRORS" -eq 0 ] && [ "$FINAL_AVAILABLE" -eq "$EXPECTED_AVAILABLE" ]; then
  echo "PASS: 100% idempotency — all $((NUM_KEYS * REPEATS_PER_KEY)) duplicate requests returned same orderId."
  echo "      Inventory deducted exactly once per key ($NUM_KEYS times total)."
else
  echo "FAIL: mismatch=$MISMATCH errors=$ERRORS inventory_check=$([ "$FINAL_AVAILABLE" -eq "$EXPECTED_AVAILABLE" ] && echo 'PASS' || echo 'FAIL')"
fi