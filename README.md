# OrderOps

A distributed order fulfillment backend demonstrating reliability and consistency patterns:
concurrent inventory reservation, idempotency, async worker, retry/DLQ, and observability.

Built with Java 17, Spring Boot 3, DynamoDB, SQS, and Redis.

---

## Architecture

```
┌──────────────┐   POST /orders    ┌─────────────────────────┐
│   Client     │ ───────────────▶  │   API Service (ECS)      │
│              │   GET /orders/:id │                          │
│              │ ◀─────────────── │  OrderController         │
└──────────────┘                   │  OrderService            │
                                   │  IdempotencyService      │
                                   └──────┬──────────┬────────┘
                                          │          │
                                     DynamoDB      Redis
                                    (Orders,       (idem
                                    Inventory,      cache)
                                    Idempotency,
                                    AuditLogs)
                                          │
                                        SQS
                                   (order-fulfillment-queue)
                                          │
                                   ┌──────▼────────────────────┐
                                   │   Worker Service (ECS)     │
                                   │                            │
                                   │  FulfillmentWorker (poll)  │
                                   │  OrderFulfillmentService   │
                                   │  PaymentSimulator          │
                                   │  ShipmentSimulator         │
                                   └────────────────────────────┘
                                          │
                                     DLQ (after 3 retries)
```

### Order State Machine

```
CREATED → INVENTORY_RESERVED → PAYMENT_PROCESSING → PAYMENT_SUCCEEDED → SHIPMENT_PROCESSING → FULFILLED
                                        │                                        │
                                        ▼                                        ▼
                                      FAILED ──────────────────────────▶ NEEDS_MANUAL_REVIEW
```

---

## Tech Stack

| Layer        | Technology                          |
|--------------|-------------------------------------|
| API          | Java 17, Spring Boot 3, Lombok      |
| Database     | AWS DynamoDB (conditional writes)   |
| Queue        | AWS SQS + DLQ                       |
| Cache        | Redis (idempotency fast path)       |
| Metrics      | Spring Boot Actuator + Micrometer   |
| Tests        | JUnit 5, Mockito, DynamoDB Local    |
| Load Tests   | k6                                  |
| CI/CD        | GitHub Actions → ECR → ECS/Fargate  |

---

## Local Setup

### Prerequisites

- Java 21 (Amazon Corretto recommended)
- Docker
- [k6](https://k6.io/docs/getting-started/installation/) (for load tests)

### Start local infrastructure

```bash
make local-up     # starts DynamoDB Local, LocalStack (SQS), Redis
make local-init   # creates DynamoDB tables and SQS queues
```

### Run the API

```bash
make build
APP_MODE=api java -jar apps/orderops/target/orderops-*.jar
```

### Run the worker (separate terminal)

```bash
APP_MODE=worker java -jar apps/orderops/target/orderops-*.jar
```

### Run tests

```bash
make test
```

---

## API Reference

### Create order

```
POST /orders
Idempotency-Key: <uuid>   (optional)

{
  "customerId": "customer-1",
  "items": [{ "itemId": "widget-a", "quantity": 2 }]
}

201 Created → { "orderId": "...", "status": "INVENTORY_RESERVED", "createdAt": "..." }
409 Conflict → insufficient inventory, or idempotency key reused with a different body
```

### Get order

```
GET /orders/{orderId}

200 OK → { "orderId": "...", "customerId": "...", "status": "FULFILLED", ... }
404 Not Found
```

### Seed inventory (local / testing)

```
POST /inventory/seed
{ "itemId": "widget-a", "quantity": 100 }
```

### Metrics

```
GET /actuator/health
GET /actuator/metrics
GET /actuator/metrics/orders.created
GET /actuator/metrics/fulfillment.fulfilled
GET /actuator/metrics/fulfillment.transient_failure
```

---

## Load Tests

### Concurrent checkout — zero oversell

Fires 1000 virtual users concurrently against 100 units of inventory.

```bash
make load-test
```

Expected result: exactly 100 orders succeed (HTTP 201), 900 rejected (HTTP 409), zero 5xx.

### Failure injection

Seeds 10 units, fires 200 VUs, verifies the API returns only 201/409 with no errors.

```bash
make failure-test
```

---

## AWS Deployment

### One-time setup

```bash
# configure AWS CLI, then:
bash infra/ecs/setup.sh

# store ElastiCache endpoint
aws ssm put-parameter \
  --name /orderops/redis-host \
  --value <elasticache-endpoint> \
  --type SecureString
```

### CI/CD

Every push to `main`:
1. Tests run (`mvn test`)
2. Docker image built and pushed to ECR
3. ECS API and worker services updated (rolling deploy, waits for stability)

Required GitHub secrets:
- `AWS_ROLE_ARN` — IAM role ARN with ECR push + ECS deploy permissions (OIDC)

### CloudWatch alarms

```bash
bash infra/ecs/cloudwatch-alarms.sh
```

Alarms created:
- CPU > 80% on API and worker services
- DLQ message count ≥ 1 (indicates persistent fulfillment failures)
- Queue message age > 5 min (indicates worker is down)
- Running task count < 1 on either service

---

## Key Design Decisions

**Conditional DynamoDB writes for inventory**
`UpdateItem` with `availableQuantity >= :requested` condition prevents oversell without locks,
even under concurrent requests.

**Idempotency two-layer cache**
Redis fast path (24-hour TTL) with DynamoDB as the persistent source of truth. Same key +
same body returns the original response; same key + different body returns 409.

**Resume-aware fulfillment worker**
If a transient failure leaves an order in `PAYMENT_PROCESSING`, the next SQS redelivery
resumes from that state instead of restarting, preventing double-charging.

**SQS at-least-once + worker idempotency**
Terminal orders (`FULFILLED`, `NEEDS_MANUAL_REVIEW`) are skipped on duplicate delivery.

---

## Benchmark Results

All tests run locally with Docker Compose (DynamoDB Local, LocalStack SQS, Redis).

### Throughput & Latency (20 VUs, 90s sustained)

| Metric | Value |
|--------|-------|
| Total orders created | 44,788 |
| Throughput | 497 req/s |
| Avg latency | 40 ms |
| p90 latency | 57 ms |
| p95 latency | 67 ms |
| p99 latency | 121 ms |
| Error rate | 0% |

### Concurrent Checkout Correctness (1,000 VUs, 100 inventory units)

| Metric | Value |
|--------|-------|
| Concurrent requests | 1,000 |
| Successful reservations | 100 |
| Rejected (out of stock) | 900 |
| Oversold inventory | 0 |
| 5xx errors | 0 |

### Idempotency (10 keys × 10 duplicate requests)

| Metric | Value |
|--------|-------|
| Original orders created | 10 |
| Duplicate requests sent | 100 |
| Returned same orderId | 100 (100%) |
| Extra inventory deductions | 0 |

### Transient Failure Retry Recovery (50 orders, 10% random failure rate)

| Metric | Value |
|--------|-------|
| Orders submitted | 50 |
| FULFILLED | 50 (100%) |
| DLQ messages | 0 |
| No silent loss | ✓ |

### Permanent Poison Message → DLQ (10 always-failing orders, maxReceiveCount=3)

| Metric | Value |
|--------|-------|
| Poison orders submitted | 10 |
| Routed to DLQ | 10 (100%) |
| Source queue after test | 0 |
| Worker attempt logs | ~30 (3 per message) |
Transient failures re-throw so SQS redelivers; after 3 attempts the message goes to the DLQ.