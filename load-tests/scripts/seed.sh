#!/usr/bin/env bash
# Usage: seed.sh [BASE_URL] [ITEM_ID] [QUANTITY] [UNIT_PRICE]
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
ITEM_ID="${2:-${ITEM_ID:-load-test-item}}"
QUANTITY="${3:-${QUANTITY:-100}}"
UNIT_PRICE="${4:-${UNIT_PRICE:-19.99}}"

echo "Seeding inventory: itemId=$ITEM_ID quantity=$QUANTITY unitPrice=$UNIT_PRICE"

curl -sf -X POST "$BASE_URL/api/v1/inventory/seed" \
  -H 'Content-Type: application/json' \
  -d "{\"itemId\": \"$ITEM_ID\", \"quantity\": $QUANTITY, \"unitPrice\": $UNIT_PRICE}"

echo
echo "Done."