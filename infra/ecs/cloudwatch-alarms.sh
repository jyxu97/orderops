#!/usr/bin/env bash
# Creates CloudWatch alarms for the OrderOps ECS services.
# Requires: AWS CLI, SNS topic ARN for notifications (optional).
set -euo pipefail

REGION="${AWS_REGION:-us-west-2}"
CLUSTER="orderops"
SNS_ARN="${SNS_ARN:-}"   # optional: set to receive alarm notifications
NAMESPACE="${CLOUDWATCH_NAMESPACE:-OrderOps}"   # must match the app's metrics namespace
# ALB target group / load balancer dimension values, from `setup.sh` output. The ALB alarms are
# skipped when these are unset, so this script stays runnable before the ALB exists.
ALB_ID="${ALB_ID:-}"                # e.g. app/orderops-alb/0123456789abcdef
TARGET_GROUP_ID="${TARGET_GROUP_ID:-}"  # e.g. targetgroup/orderops-api/0123456789abcdef
BACKLOG_THRESHOLD="${BACKLOG_THRESHOLD:-100}"   # keep in sync with ops.queue-health.backlog-threshold

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

# ── SQS main queue backlog ────────────────────────────────────────────────────
# Depth and age answer different questions: a deep queue that is draining is a capacity
# signal, while an old message means consumption has stalled regardless of depth. Both are
# alarmed because either alone misses a real outage.
alarm "orderops-queue-backlog" \
  --namespace AWS/SQS \
  --metric-name ApproximateNumberOfMessagesVisible \
  --dimensions Name=QueueName,Value=order-fulfillment-queue \
  --statistic Maximum \
  --period 60 --evaluation-periods 3 \
  --threshold "$BACKLOG_THRESHOLD" --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

# ── Fulfillment failures (application metric) ─────────────────────────────────
# Published by the worker via micrometer-registry-cloudwatch2. A counter arrives as a rate per
# step, so Sum over the period is the failure count in that window.
alarm "orderops-fulfillment-failures" \
  --namespace "$NAMESPACE" \
  --metric-name "fulfillment.transient_failure.count" \
  --statistic Sum \
  --period 300 --evaluation-periods 1 \
  --threshold 10 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

# Orders the worker gave up on. Unlike the transient counter, any sustained rate here means
# customers are being left in manual review.
alarm "orderops-orders-needing-review" \
  --namespace "$NAMESPACE" \
  --metric-name "fulfillment.manual_review.count" \
  --statistic Sum \
  --period 300 --evaluation-periods 1 \
  --threshold 5 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

# ── Real-time delivery health (application metric) ────────────────────────────
# The API cannot fan events out if the Redis bridge never subscribed. The publish-failure
# counter is the signal: order state stays correct, but every client goes stale.
alarm "orderops-realtime-publish-failures" \
  --namespace "$NAMESPACE" \
  --metric-name "realtime.events.publish_failed.count" \
  --statistic Sum \
  --period 300 --evaluation-periods 1 \
  --threshold 5 --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching

# ── ALB: API error rate and latency ───────────────────────────────────────────
if [ -n "$ALB_ID" ] && [ -n "$TARGET_GROUP_ID" ]; then
  alarm "orderops-api-5xx" \
    --namespace AWS/ApplicationELB \
    --metric-name HTTPCode_Target_5XX_Count \
    --dimensions Name=LoadBalancer,Value="$ALB_ID" Name=TargetGroup,Value="$TARGET_GROUP_ID" \
    --statistic Sum \
    --period 60 --evaluation-periods 2 \
    --threshold 10 --comparison-operator GreaterThanThreshold \
    --treat-missing-data notBreaching

  # p95 rather than Average: an average hides a slow tail behind fast health checks.
  alarm "orderops-api-latency-p95" \
    --namespace AWS/ApplicationELB \
    --metric-name TargetResponseTime \
    --dimensions Name=LoadBalancer,Value="$ALB_ID" Name=TargetGroup,Value="$TARGET_GROUP_ID" \
    --extended-statistic p95 \
    --period 300 --evaluation-periods 2 \
    --threshold 2 --comparison-operator GreaterThanThreshold \
    --treat-missing-data notBreaching

  alarm "orderops-api-unhealthy-targets" \
    --namespace AWS/ApplicationELB \
    --metric-name UnHealthyHostCount \
    --dimensions Name=LoadBalancer,Value="$ALB_ID" Name=TargetGroup,Value="$TARGET_GROUP_ID" \
    --statistic Maximum \
    --period 60 --evaluation-periods 2 \
    --threshold 0 --comparison-operator GreaterThanThreshold \
    --treat-missing-data notBreaching
else
  echo "  skipped ALB alarms (set ALB_ID and TARGET_GROUP_ID to create them)"
fi

echo ""
echo "=== Alarms created ==="
echo ""
echo "Application-metric alarms need the app publishing to CloudWatch:"
echo "  CLOUDWATCH_METRICS_ENABLED=true  CLOUDWATCH_NAMESPACE=$NAMESPACE"
echo "and the task role needs cloudwatch:PutMetricData." 