#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="http://localhost:4566"
REGION="us-west-2"

echo "Creating SQS queues..."

# DLQ first (main queue references its ARN)
aws --endpoint-url "$ENDPOINT" --region "$REGION" sqs create-queue \
    --queue-name order-fulfillment-dlq

DLQ_ARN=$(aws --endpoint-url "$ENDPOINT" --region "$REGION" sqs get-queue-attributes \
    --queue-url "$ENDPOINT/000000000000/order-fulfillment-dlq" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' --output text)

# Main queue: route to DLQ after 3 failed receives
aws --endpoint-url "$ENDPOINT" --region "$REGION" sqs create-queue \
    --queue-name order-fulfillment-queue \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"

echo "SQS queues ready:"
echo "  order-fulfillment-queue  (maxReceiveCount=3 -> DLQ)"
echo "  order-fulfillment-dlq"