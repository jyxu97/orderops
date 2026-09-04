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
echo "[1/7] Creating ECR repository..."
aws ecr create-repository \
  --repository-name "$ECR_REPO" \
  --region "$REGION" \
  --image-scanning-configuration scanOnPush=true \
  2>/dev/null || echo "  (already exists)"

# ── ECS Cluster ───────────────────────────────────────────────────────────────
echo "[2/7] Creating ECS cluster..."
aws ecs create-cluster \
  --cluster-name "$CLUSTER" \
  --capacity-providers FARGATE \
  --region "$REGION" \
  2>/dev/null || echo "  (already exists)"

# ── CloudWatch Log Group ──────────────────────────────────────────────────────
echo "[3/7] Creating CloudWatch log group..."
aws logs create-log-group \
  --log-group-name "$LOG_GROUP" \
  --region "$REGION" \
  2>/dev/null || echo "  (already exists)"
aws logs put-retention-policy \
  --log-group-name "$LOG_GROUP" \
  --retention-in-days 14 \
  --region "$REGION"

# ── DynamoDB Tables ───────────────────────────────────────────────────────────
echo "[4/7] Creating DynamoDB tables..."
bash "$(dirname "$0")/../dynamodb/create-tables.sh"

# ── SQS Queues ────────────────────────────────────────────────────────────────
echo "[5/7] Creating SQS queues..."
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

# ── IAM task role ─────────────────────────────────────────────────────────────
# The task definitions reference orderopsTaskRole, so it has to exist before they register.
# Scoped to the resources this app actually touches rather than the managed
# AmazonDynamoDBFullAccess/AmazonSQSFullAccess policies: a leaked task credential should not be
# able to read or drop tables belonging to anything else in the account.
echo "[6/7] Creating the task role..."
TASK_ROLE="orderopsTaskRole"

aws iam create-role \
  --role-name "$TASK_ROLE" \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": { "Service": "ecs-tasks.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }]
  }' 2>/dev/null || echo "  (already exists)"

aws iam put-role-policy \
  --role-name "$TASK_ROLE" \
  --policy-name orderops-task-policy \
  --policy-document "$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "OrdersAndInventory",
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:BatchGetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:TransactWriteItems",
        "dynamodb:TransactGetItems"
      ],
      "Resource": [
        "arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/Orders",
        "arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/Orders/index/*",
        "arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/Inventory",
        "arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/IdempotencyRecords",
        "arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/OrderAuditLogs"
      ]
    },
    {
      "Sid": "FulfillmentQueue",
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:ChangeMessageVisibility",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl"
      ],
      "Resource": [
        "arn:aws:sqs:$REGION:$ACCOUNT_ID:order-fulfillment-queue",
        "arn:aws:sqs:$REGION:$ACCOUNT_ID:order-fulfillment-dlq"
      ]
    },
    {
      "Sid": "DeadLetterRedrive",
      "Effect": "Allow",
      "Action": [
        "sqs:StartMessageMoveTask",
        "sqs:ListMessageMoveTasks",
        "sqs:CancelMessageMoveTask"
      ],
      "Resource": "arn:aws:sqs:$REGION:$ACCOUNT_ID:order-fulfillment-dlq"
    },
    {
      "Sid": "PublishOwnMetrics",
      "Effect": "Allow",
      "Action": "cloudwatch:PutMetricData",
      "Resource": "*",
      "Condition": {
        "StringEquals": { "cloudwatch:namespace": "OrderOps" }
      }
    },
    {
      "Sid": "ReadConfig",
      "Effect": "Allow",
      "Action": ["ssm:GetParameter", "ssm:GetParameters"],
      "Resource": "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter/orderops/*"
    }
  ]
}
JSON
)"
echo "  policy attached to $TASK_ROLE"

# ── ECS Services ──────────────────────────────────────────────────────────────
echo "[7/7] Creating ECS services..."
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
echo "  4. Create the ALB and target group, then note the DNS name and ARNs"
echo "  5. Front-end hosting:  ALB_DNS=<alb-dns> bash infra/frontend/setup.sh"
echo "  6. Alarms:  ALB_ID=<...> TARGET_GROUP_ID=<...> bash infra/ecs/cloudwatch-alarms.sh"
echo "  7. Verify:  BASE_URL=http://<alb-dns> bash infra/smoke-test.sh"