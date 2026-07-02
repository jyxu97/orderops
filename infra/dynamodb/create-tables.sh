#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"
REGION="${AWS_REGION:-us-west-2}"

echo "Creating DynamoDB tables at $ENDPOINT..."

aws dynamodb create-table \
  --endpoint-url "$ENDPOINT" \
  --region "$REGION" \
  --table-name Orders \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
  --key-schema \
    AttributeName=orderId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --no-cli-pager 2>/dev/null || echo "Orders table already exists"

aws dynamodb create-table \
  --endpoint-url "$ENDPOINT" \
  --region "$REGION" \
  --table-name Inventory \
  --attribute-definitions \
    AttributeName=itemId,AttributeType=S \
  --key-schema \
    AttributeName=itemId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --no-cli-pager 2>/dev/null || echo "Inventory table already exists"

aws dynamodb create-table \
  --endpoint-url "$ENDPOINT" \
  --region "$REGION" \
  --table-name IdempotencyRecords \
  --attribute-definitions \
    AttributeName=idempotencyKey,AttributeType=S \
  --key-schema \
    AttributeName=idempotencyKey,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --no-cli-pager 2>/dev/null || echo "IdempotencyRecords table already exists"

aws dynamodb create-table \
  --endpoint-url "$ENDPOINT" \
  --region "$REGION" \
  --table-name OrderAuditLogs \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=timestamp,AttributeType=S \
  --key-schema \
    AttributeName=orderId,KeyType=HASH \
    AttributeName=timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --no-cli-pager 2>/dev/null || echo "OrderAuditLogs table already exists"

echo "Done."
