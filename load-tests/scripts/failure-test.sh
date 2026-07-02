#!/usr/bin/env bash
# Failure injection test: seeds 10 units, fires 200 concurrent requests,
# verifies at most 10 succeed and zero 5xx errors are returned.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ITEM_ID="failure-test-item"

echo "=== Failure Injection Test ==="
echo "Seeding inventory: itemId=$ITEM_ID quantity=10"

curl -sf -X POST "$BASE_URL/inventory/seed" \
  -H 'Content-Type: application/json' \
  -d "{\"itemId\": \"$ITEM_ID\", \"quantity\": 10}"
echo

echo "Running k6 failure injection (200 VUs, 10-unit stock)..."
k6 run \
  -e BASE_URL="$BASE_URL" \
  -e ITEM_ID="$ITEM_ID" \
  load-tests/k6/failure-injection.js