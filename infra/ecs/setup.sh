#!/usr/bin/env bash
# One-time AWS infrastructure setup for OrderOps.
# Run this once before the first deployment.
#
# Prerequisites:
#   - AWS CLI configured with admin credentials
#   - ACCOUNT_ID set or discoverable via STS
#   - VPC_ID, SUBNET_IDS, SECURITY_GROUP_ID set as env vars
set -euo pipefail

REGION="${AWS_REGION:-us-west-2}"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
CLUSTER="orderops"
ECR_REPO="orderops"
LOG_GROUP="/ecs/orderops"

echo "=== OrderOps AWS Setup (account=$ACCOUNT_ID region=$REGION) ==="

# ── ECR ──────────────────────────────────────────────────────────────────────
echo "[1/6] Creating ECR repository..."
aws ecr create-repository \
  --repository-name "$ECR_REPO" \
  --region "$REGION" \
  --image-scanning-configuration scanOnPush=true \
  2>/dev/null || echo "  (already exists)"

# ── ECS Cluster ───────────────────────────────────────────────────────────────
echo "[2/6] Creating ECS cluster..."
aws ecs create-cluster \
  --cluster-name "$CLUSTER" \
  --capacity-providers FARGATE \
  --region "$REGION" \
  2>/dev/null || echo "  (already exists)"

# ── CloudWatch Log Group ──────────────────────────────────────────────────────
echo "[3/6] Creating CloudWatch log group..."
aws logs create-log-group \
  --log-group-name "$LOG_GROUP" \
  --region "$REGION" \
  2>/dev/null || echo "  (already exists)"
aws logs put-retention-policy \
  --log-group-name "$LOG_GROUP" \
  --retention-in-days 14 \
  --region "$REGION"

# ── DynamoDB Tables ───────────────────────────────────────────────────────────
echo "[4/6] Creating DynamoDB tables..."
bash "$(dirname "$0")/../dynamodb/create-tables.sh"

# ── SQS Queues ────────────────────────────────────────────────────────────────
echo "[5/6] Creating SQS queues..."
DLQ_URL=$(aws sqs create-queue \
  --queue-name order-fulfillment-dlq \
  --region "$REGION" \
  --query QueueUrl --output text)

DLQ_ARN=$(aws sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

aws sqs create-queue \
  --queue-name order-fulfillment-queue \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" \
  --region "$REGION" > /dev/null

QUEUE_URL="https://sqs.$REGION.amazonaws.com/$ACCOUNT_ID/order-fulfillment-queue"
DLQ_FULL_URL="https://sqs.$REGION.amazonaws.com/$ACCOUNT_ID/order-fulfillment-dlq"

# Store queue URLs in SSM Parameter Store for ECS secrets
aws ssm put-parameter --name /orderops/fulfillment-queue-url --value "$QUEUE_URL"  --type String --overwrite --region "$REGION"
aws ssm put-parameter --name /orderops/fulfillment-dlq-url   --value "$DLQ_FULL_URL" --type String --overwrite --region "$REGION"

# ── ECS Services ──────────────────────────────────────────────────────────────
echo "[6/6] Creating ECS services..."
echo "  Registering task definitions..."
sed "s/YOUR_ACCOUNT_ID/$ACCOUNT_ID/g" "$(dirname "$0")/api-task-def.json" | \
  aws ecs register-task-definition --cli-input-json file:///dev/stdin --region "$REGION" > /dev/null
sed "s/YOUR_ACCOUNT_ID/$ACCOUNT_ID/g" "$(dirname "$0")/worker-task-def.json" | \
  aws ecs register-task-definition --cli-input-json file:///dev/stdin --region "$REGION" > /dev/null

echo ""
echo "=== Setup complete ==="
echo ""
echo "Next steps:"
echo "  1. Store ElastiCache endpoint:  aws ssm put-parameter --name /orderops/redis-host --value <endpoint> --type SecureString"
echo "  2. Push a Docker image to ECR to trigger the first ECS deployment via GitHub Actions"
echo "  3. Create ECS services (API + worker) in the console or via AWS CLI, referencing"
echo "     the registered task definitions, your VPC, subnets, and security groups"