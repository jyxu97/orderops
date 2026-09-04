#!/usr/bin/env bash
# One-time setup for hosting the React app on S3 behind CloudFront.
#
# Why S3 + CloudFront rather than a third ECS service: the build output is static files. Running
# a Fargate task to serve them would pay for an always-on container, a load balancer target and
# a health check to do a job object storage does better and cheaper. The container image in
# apps/web/Dockerfile still exists — it is what `make app-up` uses to run the whole stack
# locally — it just is not what production serves.
#
# The distribution is given TWO origins:
#
#   /*      -> S3 bucket (the SPA)
#   /api/*  -> ALB (the Spring Boot API)
#   /ws     -> ALB (the STOMP WebSocket)
#
# That is what keeps the browser on a single origin, so the app's fetch and WebSocket URLs stay
# relative and CORS is off the critical path. The CORS config in the API remains for the split
# origin case (a Vite dev server, or an API on its own hostname).
set -euo pipefail

REGION="${AWS_REGION:-us-west-2}"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET="${FRONTEND_BUCKET:-orderops-web-$ACCOUNT_ID}"
ALB_DNS="${ALB_DNS:-}"   # e.g. orderops-alb-123456789.us-west-2.elb.amazonaws.com

if [ -z "$ALB_DNS" ]; then
  echo "ALB_DNS is required — the distribution needs an origin to send /api and /ws to." >&2
  echo "Find it with: aws elbv2 describe-load-balancers --query 'LoadBalancers[0].DNSName'" >&2
  exit 1
fi

echo "=== OrderOps frontend setup (bucket=$BUCKET region=$REGION) ==="

echo "[1/4] Creating the S3 bucket..."
if [ "$REGION" = "us-east-1" ]; then
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" 2>/dev/null || echo "  (already exists)"
else
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=$REGION" 2>/dev/null || echo "  (already exists)"
fi

# The bucket stays private; CloudFront reaches it through an Origin Access Control. A public
# bucket would let anyone bypass the distribution and hit S3 directly.
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

echo "[2/4] Creating the Origin Access Control..."
OAC_ID=$(aws cloudfront create-origin-access-control \
  --origin-access-control-config \
  "Name=orderops-web-oac,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3" \
  --query 'OriginAccessControl.Id' --output text 2>/dev/null \
  || aws cloudfront list-origin-access-controls \
       --query "OriginAccessControlList.Items[?Name=='orderops-web-oac'].Id | [0]" --output text)
echo "  OAC: $OAC_ID"

echo "[3/4] Creating the distribution..."
DIST_CONFIG=$(mktemp)
cat > "$DIST_CONFIG" <<JSON
{
  "CallerReference": "orderops-web-$(date +%s)",
  "Comment": "OrderOps web app + API",
  "Enabled": true,
  "DefaultRootObject": "index.html",
  "Origins": {
    "Quantity": 2,
    "Items": [
      {
        "Id": "s3-web",
        "DomainName": "$BUCKET.s3.$REGION.amazonaws.com",
        "OriginAccessControlId": "$OAC_ID",
        "S3OriginConfig": { "OriginAccessIdentity": "" }
      },
      {
        "Id": "alb-api",
        "DomainName": "$ALB_DNS",
        "CustomOriginConfig": {
          "HTTPPort": 80,
          "HTTPSPort": 443,
          "OriginProtocolPolicy": "http-only",
          "OriginSslProtocols": { "Quantity": 1, "Items": ["TLSv1.2"] }
        }
      }
    ]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "s3-web",
    "ViewerProtocolPolicy": "redirect-to-https",
    "AllowedMethods": { "Quantity": 2, "Items": ["GET", "HEAD"] },
    "Compress": true,
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6"
  },
  "CacheBehaviors": {
    "Quantity": 2,
    "Items": [
      {
        "PathPattern": "/api/*",
        "TargetOriginId": "alb-api",
        "ViewerProtocolPolicy": "https-only",
        "AllowedMethods": {
          "Quantity": 7,
          "Items": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
        },
        "CachePolicyId": "4135ea2d-6df8-44a3-9df3-4b5a84be39ad",
        "OriginRequestPolicyId": "216adef6-5c7f-47e4-b989-5492eafa07d3"
      },
      {
        "PathPattern": "/ws",
        "TargetOriginId": "alb-api",
        "ViewerProtocolPolicy": "https-only",
        "AllowedMethods": {
          "Quantity": 7,
          "Items": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
        },
        "CachePolicyId": "4135ea2d-6df8-44a3-9df3-4b5a84be39ad",
        "OriginRequestPolicyId": "216adef6-5c7f-47e4-b989-5492eafa07d3"
      }
    ]
  },
  "CustomErrorResponses": {
    "Quantity": 2,
    "Items": [
      {
        "ErrorCode": 403,
        "ResponseCode": "200",
        "ResponsePagePath": "/index.html",
        "ErrorCachingMinTTL": 0
      },
      {
        "ErrorCode": 404,
        "ResponseCode": "200",
        "ResponsePagePath": "/index.html",
        "ErrorCachingMinTTL": 0
      }
    ]
  }
}
JSON

DIST_ID=$(aws cloudfront create-distribution --distribution-config "file://$DIST_CONFIG" \
  --query 'Distribution.Id' --output text)
DIST_DOMAIN=$(aws cloudfront get-distribution --id "$DIST_ID" \
  --query 'Distribution.DomainName' --output text)
rm -f "$DIST_CONFIG"
echo "  distribution: $DIST_ID ($DIST_DOMAIN)"

echo "[4/4] Granting the distribution read access to the bucket..."
aws s3api put-bucket-policy --bucket "$BUCKET" --policy "$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "AllowCloudFrontRead",
    "Effect": "Allow",
    "Principal": { "Service": "cloudfront.amazonaws.com" },
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::$BUCKET/*",
    "Condition": {
      "StringEquals": {
        "AWS:SourceArn": "arn:aws:cloudfront::$ACCOUNT_ID:distribution/$DIST_ID"
      }
    }
  }]
}
JSON
)"

cat <<OUT

=== Frontend infrastructure ready ===

  App URL          https://$DIST_DOMAIN
  Bucket           $BUCKET
  Distribution ID  $DIST_ID

Set these as GitHub Actions repository variables so the deploy workflow can find them:

  FRONTEND_BUCKET          $BUCKET
  CLOUDFRONT_DISTRIBUTION  $DIST_ID

Notes:
  - The 403/404 -> /index.html rules are what make client-side routing work: any unknown path
    is a React route, not a missing object.
  - /api/* and /ws use the caching-disabled managed policy and the AllViewer origin request
    policy, so headers (including Idempotency-Key and the WebSocket upgrade) reach the ALB.
  - A distribution takes several minutes to deploy before the URL responds.
OUT
