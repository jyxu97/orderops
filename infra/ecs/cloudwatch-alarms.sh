#!/usr/bin/env bash
# Creates CloudWatch alarms for the OrderOps ECS services.
# Requires: AWS CLI, SNS topic ARN for notifications (optional).
set -euo pipefail

REGION="${AWS_REGION:-us-west-2}"
CLUSTER="orderops"
SNS_ARN="${SNS_ARN:-}"   # optional: set to receive alarm notifications

alarm() {
  local name="$1"; shift
  aws cloudwatch put-metric-alarm \
    --region "$REGION" \
    --alarm-name "$name" \
    ${SNS_ARN:+--alarm-actions "$SNS_ARN"} \
    "$@"
  echo "  created: $name"
}

echo "=== Creating CloudWatch alarms ==="

# ── ECS CPU utilisation ───────────────────────────────────────────────────────
alarm "orderops-api-cpu-high" \
  --namespace AWS/ECS \
  --metric-name CPUUtilization \
  --dimensions Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=orderops-api \
  --statistic Average \
  --period 60 --evaluation-periods 3 \
  --threshold 80 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

alarm "orderops-worker-cpu-high" \
  --namespace AWS/ECS \
  --metric-name CPUUtilization \
  --dimensions Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=orderops-worker \
  --statistic Average \
  --period 60 --evaluation-periods 3 \
  --threshold 80 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

# ── SQS DLQ depth (messages entering the DLQ indicate persistent failures) ────
alarm "orderops-dlq-not-empty" \
  --namespace AWS/SQS \
  --metric-name ApproximateNumberOfMessagesVisible \
  --dimensions Name=QueueName,Value=order-fulfillment-dlq \
  --statistic Sum \
  --period 60 --evaluation-periods 1 \
  --threshold 1 --comparison-operator GreaterThanOrEqualToThreshold \
  --treat-missing-data notBreaching

# ── SQS main queue age (messages stuck waiting indicate a worker outage) ──────
alarm "orderops-queue-message-age" \
  --namespace AWS/SQS \
  --metric-name ApproximateAgeOfOldestMessage \
  --dimensions Name=QueueName,Value=order-fulfillment-queue \
  --statistic Maximum \
  --period 300 --evaluation-periods 2 \
  --threshold 300 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

# ── ECS running task count (detect crashed services) ─────────────────────────
alarm "orderops-api-no-tasks" \
  --namespace AWS/ECS \
  --metric-name RunningTaskCount \
  --dimensions Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=orderops-api \
  --statistic Minimum \
  --period 60 --evaluation-periods 2 \
  --threshold 1 --comparison-operator LessThanThreshold \
  --treat-missing-data breaching

alarm "orderops-worker-no-tasks" \
  --namespace AWS/ECS \
  --metric-name RunningTaskCount \
  --dimensions Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=orderops-worker \
  --statistic Minimum \
  --period 60 --evaluation-periods 2 \
  --threshold 1 --comparison-operator LessThanThreshold \
  --treat-missing-data breaching

echo ""
echo "=== Alarms created ==="