# OrderOps

A distributed order fulfillment backend demonstrating reliability and consistency patterns:
concurrent inventory reservation, idempotency, async worker, retry/DLQ, and observability.

Built with Java 17, Spring Boot 3, DynamoDB, SQS, and Redis.

---

## Architecture

```
┌──────────────┐  POST /api/v1/…   ┌─────────────────────────┐
│   Client     │ ───────────────▶  │   API Service (ECS)      │
│              │  GET  /api/v1/…   │                          │
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
                  │                     │                                        │
                  │                     ▼                                        ▼
                  │                   FAILED ──────────────────────────▶ NEEDS_MANUAL_REVIEW
                  │                                                               │
                  ▼                                                               ▼
              CANCELLED ◀─────────────────────────────────────────────────────────┘
```

`CANCELLED` is reachable from two places, and only those two:

- **`INVENTORY_RESERVED`** — a customer cancelling before the worker starts charging. Once
  payment is in flight the order belongs to the fulfillment pipeline and cannot be pulled back.
- **`NEEDS_MANUAL_REVIEW`** — an operator resolving a failed order, which returns its held
  stock to the catalog.

Cancelling releases every line item's reservation and flips the order status in a single
`TransactWriteItems` call, so stock can never be released without the order being cancelled
(or vice versa). The release carries a `reservedQuantity >= :qty` condition, which is what
makes a double release abort rather than conjure stock out of nothing.

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

All endpoints are versioned under `/api/v1`.

### Create order

```
POST /api/v1/orders
Idempotency-Key: <uuid>   (optional)

{
  "customerId": "customer-1",
  "items": [{ "itemId": "widget-a", "quantity": 2 }]
}

201 Created → { "orderId": "...", "status": "INVENTORY_RESERVED", "totalAmount": 39.98, "createdAt": "..." }
200 OK      → idempotent replay of a previous request (same key, same body)
400 Bad Request → validation failure, with a `fieldErrors` map
409 Conflict → insufficient inventory, or idempotency key reused with a different body
```

### Get order

```
GET /api/v1/orders/{orderId}

200 OK → { "orderId": "...", "customerId": "...", "status": "FULFILLED", ... }
404 Not Found
```

Line items carry the `unitPrice` snapshotted at checkout, so a later catalog price change
cannot rewrite the value of an existing order.

### List orders

Exactly one filter is required. Both are served by a GSI, so neither degrades into a scan.

```
GET /api/v1/orders?customerId=customer-1&limit=25&cursor=<opaque>
GET /api/v1/orders?status=NEEDS_MANUAL_REVIEW&limit=25

200 OK → { "items": [ { "orderId": "...", "status": "...", "totalQuantity": 3, ... } ],
             "nextCursor": "<opaque>" }   // nextCursor absent = end of list
400 Bad Request → neither or both filters supplied, unknown status, limit out of 1..100
```

### Cancel order

```
POST /api/v1/orders/{orderId}/cancel

200 OK       → order state after cancellation (also returned when already cancelled)
404 Not Found
409 Conflict → the order has moved too far through fulfillment to cancel
```

Idempotent: a client retrying after a timeout gets 200 and the stock is released exactly once.

### Inventory

```
GET  /api/v1/inventory?limit=50        → catalog listing
GET  /api/v1/inventory/{itemId}        → 200 OK / 404 Not Found
POST /api/v1/inventory/seed            → { "itemId": "widget-a", "quantity": 100, "unitPrice": 19.99 }
```

### Order audit timeline

```
GET /api/v1/orders/{orderId}/audit

200 OK → [ { "timestamp": "...", "fromStatus": "CREATED",
             "toStatus": "INVENTORY_RESERVED", "reason": "Order created" }, ... ]
404 Not Found
```

Oldest first. The `OrderAuditLogs` sort key is the ISO-8601 timestamp, so index order is
already chronological — nothing is sorted client-side.

### Operations

```
GET /api/v1/ops/overview?recentLimit=20   → status counts + recent orders + queue depth
GET /api/v1/ops/orders?limit=25           → most recently updated orders, all statuses
GET /api/v1/ops/failures?limit=25         → failed orders joined with their last failure reason
GET /api/v1/ops/queue-health              → queue and DLQ depth, with threshold warnings
```

Notes on how these are served:

- **Nothing scans the Orders table.** Every read goes through `GSI2_StatusUpdatedAt`.
- **"Recent orders across all statuses" is a bounded fan-out** — one indexed query per status,
  merged in memory. There is no index that orders every order by update time, and adding one
  would mean a single-partition index covering the whole table.
- **Status counts use `Select=COUNT`**, so items never cross the wire. The page loop is capped
  at 20 index pages; if a count stops at the cap, `countsCapped` is `true` and the totals are
  lower bounds rather than exact figures. At production scale these would be maintained as
  counters off DynamoDB Streams instead of counted on read.
- **`queue-health` degrades instead of failing.** If SQS is unreachable it returns 200 with
  `available: false` and omits `healthy` entirely — a missing reading is never rendered as a
  healthy one, and a broken metrics read does not hide the order data next to it.
- **Oldest-message age is absent on purpose.** `ApproximateAgeOfOldestMessage` is a CloudWatch
  metric, not an SQS queue attribute; reading it from SQS would mean receiving a message, which
  advances its receive count and pushes it toward the DLQ. It is alarmed on in CloudWatch.

### Real-time order tracking (WebSocket)

STOMP over a native WebSocket at `/ws`. Clients subscribe; nothing accepts a client-sent
message, so there is no `@MessageMapping` surface.

```
/topic/orders/{orderId}            → one order            (order detail page)
/topic/customers/{customerId}/orders → one customer's orders (order list page)
/topic/ops/orders                  → every order event    (operations dashboard)
```

Event payload:

```json
{
  "type": "ORDER_STATUS_CHANGED",
  "orderId": "8bc053f4-...",
  "customerId": "customer-1",
  "previousStatus": "PAYMENT_PROCESSING",
  "status": "PAYMENT_SUCCEEDED",
  "reason": "Payment authorized",
  "occurredAt": "2026-09-02T...",
  "committedAtEpochMilli": 1756...
}
```

#### Why events travel through Redis

```
Fulfillment worker (separate ECS task)
      │  state committed to DynamoDB
      ▼
Redis Pub/Sub  "orderops:order-events"
      │
      ▼
every API task subscribes
      │
      ▼
STOMP broker → connected browsers
```

The worker is a different process from the API, so it has no WebSocket sessions to push to —
Redis is the only path by which its transitions can reach a browser. Events raised by the API
itself go through Redis too, rather than straight to the local broker: the simple broker keeps
subscriptions in one instance's heap, so a locally-broadcast event would only reach the clients
that happened to land on the replica that produced it.

#### Design properties

- **Events are hints, not truth.** `publish` never fails the caller. The order is already
  committed in DynamoDB; failing a checkout because a notification could not be sent would
  trade a cosmetic problem for a real one. The UI refetches over REST after reconnecting rather
  than assuming a gap-free stream.
- **Published only after the write commits**, so a subscriber never sees a status that a
  subsequent read of DynamoDB would contradict.
- **Subscriptions are allowlisted.** A `ChannelInterceptor` drops any SUBSCRIBE frame naming a
  destination outside the three shapes above — without it, one client could subscribe to
  `/topic/**` and watch every order in the system. Honest limitation: this constrains the
  *shape* of a subscription, not the *identity* behind it. Anyone holding an order ID can watch
  that order and the ops topic is open; real per-customer authorization needs an authenticated
  principal on the STOMP session, and authentication is out of scope for this project.
- **A malformed payload does not kill the listener** — it is counted and discarded so the next
  event still lands.

### Metrics

```
GET /actuator/health
GET /actuator/metrics
GET /actuator/metrics/orders.created
GET /actuator/metrics/fulfillment.fulfilled
GET /actuator/metrics/fulfillment.transient_failure
GET /actuator/metrics/realtime.events.published
GET /actuator/metrics/realtime.events.broadcast
GET /actuator/metrics/realtime.connections.active
GET /actuator/metrics/realtime.subscriptions.rejected
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