# OrderOps

A distributed order fulfillment backend demonstrating reliability and consistency patterns:
concurrent inventory reservation, idempotency, async worker, retry/DLQ, and observability.

Built with Java 21, Spring Boot 3, React, TypeScript, DynamoDB, SQS, Redis and WebSockets.

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
| Frontend     | React 19, TypeScript, Vite, TanStack Query, React Router |
| Real-time    | STOMP over WebSocket + Redis Pub/Sub |
| API          | Java 21, Spring Boot 3, Lombok      |
| Database     | AWS DynamoDB (conditional writes)   |
| Queue        | AWS SQS + DLQ                       |
| Cache        | Redis (idempotency fast path)       |
| Metrics      | Spring Boot Actuator + Micrometer   |
| Tests        | JUnit 5, Mockito, DynamoDB Local, Vitest |
| Load Tests   | k6                                  |
| CI/CD        | GitHub Actions → ECR → ECS/Fargate  |

---

## Local Setup

### Prerequisites

- Java 21 (Amazon Corretto recommended)
- Node.js 20.11+ (see the note below on toolchain versions)
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

### Run the frontend

```bash
make web-install   # once
make web-dev       # Vite dev server on http://localhost:5173
```

The dev server proxies `/api` and `/ws` to the API on `:8080`, so the browser talks to a single
origin and the app's URLs stay relative. That is also how it runs in AWS, where one CloudFront
distribution fronts both the S3 bucket and the ALB.

Seed a catalog first, or the create-order page has nothing to add:

```bash
bash load-tests/scripts/seed.sh http://localhost:8080 widget-a 120 19.99
```

### Or run the whole stack in containers

```bash
make app-up        # API, worker, React app (nginx) + infrastructure
                   # → http://localhost:3000
make app-down
```

The application services sit behind a compose `app` profile, so plain `make local-up` still
starts infrastructure only. If port 6379 is already taken on your host, set
`ORDEROPS_REDIS_PORT` to something else.

### Run tests

```bash
make test          # backend: JUnit + DynamoDB Local
make web-test      # frontend: Vitest + React Testing Library
make web-lint
make web-typecheck
```

The frontend suite runs against real components with the API modules mocked, so it asserts
behaviour rather than implementation:

- **`connection.test.ts`** stands in a fake stompjs client to cover the parts a socket cannot
  be driven into under jsdom — that subscriptions are re-sent after a reconnect, that the
  resync notification fires on a *re*connect and not the first connect, that handlers on one
  destination share a single STOMP subscription, and that one throwing handler does not stop
  the others.
- **`CreateOrderPage.test.tsx`** pins the idempotency-key rule: a retried submit reuses the
  same key, and editing the basket mints a new one. It also asserts the page never reports a
  reservation before the server confirms it.
- **`OrderDetailPage.test.tsx`** drives a live event through the fake connection and asserts
  the page *refetches* rather than patching its cache from the event, and that cancel is
  offered strictly from the server's `cancellable` flag.
- **`OperationsPage.test.tsx`** covers the aggregate tiles, the lower-bound warning when a
  count hit its cap, that unreadable queue depth renders as unknown rather than healthy, and
  that a 50-event burst collapses into one refetch.
- **`FailuresPage.test.tsx`** covers both operator workflows and their distinct states —
  including that the page does not claim "nothing needs an operator" while the DLQ still holds
  messages.

### A note on frontend toolchain versions

Vite 7/8 require Node `^20.19.0 || >=22.12.0`, and jsdom 27+ needs `require(esm)` support that
arrived in the same Node releases. This repo is pinned to **Vite 6**, **Vitest 3** and
**jsdom 26**, which work on Node 20.11. Nothing about the project needs the newer majors — but
if you upgrade to Node 22 LTS, bumping those three pins is the only change required.

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
GET  /api/v1/ops/queue-health             → queue and DLQ depth, with threshold warnings
POST /api/v1/ops/dlq/redrive              → move dead-lettered messages back to the queue
GET  /api/v1/ops/dlq/redrive              → progress of the current or last redrive
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
- **Redrive uses the SQS message move task API**, not a receive-send-delete loop. SQS owns the
  movement, so there is no window where a message has been sent to the source queue but not yet
  deleted from the DLQ — a window a manual loop cannot close, and which becomes duplicate
  deliveries if it crashes mid-redrive. Returns 202 because the move is asynchronous; 409 when
  one is already running, since SQS permits a single move task per queue.
- **Failures report the cause, not the routing step.** An order in manual review got there via
  `FAILED → NEEDS_MANUAL_REVIEW`, and that newest audit entry reads "Queued for manual review"
  — which tells an operator nothing. The view prefers the reason on the transition *into*
  `FAILED` ("Payment declined", "Shipment failed"), falling back to the newest entry.
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
- **Redis is not a startup dependency.** The listener container is excluded from
  lifecycle-driven startup and brought up afterwards by `RedisEventBridgeStarter`, which polls
  `isListening()` and retries. Subscribing during context refresh would fail the bean and stop
  the API booting at all when Redis is down — inverting the whole consistency model, where
  losing Redis should cost live updates and nothing else.

  Two details that are easy to get wrong here:

  - `start()` is not a usable success signal. Once the container's internal running flag is
    set, a second call short-circuits and returns normally even though nothing was ever
    subscribed, so "it did not throw" reads as success while Redis is still refusing
    connections. `isListening()` is the only honest check.
  - A failed `start()` is sticky for the same reason, so each retry calls `stop()` first.
    Without that the bridge stays dead permanently once the first attempt fails, even after
    Redis comes back.

### Frontend

```
/order/create           place an order against the live catalog
/orders                 order history by customer, or all orders in one status
/orders/:orderId        order detail: items, fulfillment progress, audit timeline, cancel
/operations             status counts, queue depth, recent orders — live
/operations/failures    failed orders with their cause, plus the cancel and redrive actions
```

Notes on how the UI is wired:

- **Server state lives in TanStack Query**, UI state in components. Every query key is declared
  in `src/api/queryKeys.ts` so an invalidation cannot drift from the key a query registered under.
- **One WebSocket per tab**, shared by every component. A connection per component would mean a
  socket per mounted list row, and connection count is what decides whether the API needs
  scaling out. `RealtimeConnection` multiplexes destinations over the single socket and
  re-subscribes them after a reconnect — stompjs restores the socket but not the subscriptions.
- **Events trigger a refetch, not a cache patch.** An event carries only the new status;
  DynamoDB is the authority on what the order now looks like, version and timestamps included.
  On the status-filtered list a patch could not even express the right outcome, since a status
  change can move an order *out of* the result set.
- **Reconnect invalidates every query.** A client that was disconnected cannot know what it
  missed, so anything cached could be stale. Reconnects are rare, so broad invalidation costs
  almost nothing while a missed one would leave the UI quietly showing an old status.
- **One idempotency key per intended order, not per HTTP attempt.** If checkout times out, the
  retry carries the same key so the backend recognises it as the same order rather than
  reserving stock twice. The key is discarded on success or when the basket changes.
- **No optimistic reservation.** The create-order page never shows a reservation as succeeded
  before the server confirms one — inventory is exactly the thing that cannot be guessed.
- **Dashboard events are coalesced.** `/topic/ops/orders` carries an event for every state
  change of every order — four or five per order through fulfillment. Refetching per event
  would turn a checkout burst into a refetch storm against the API the burst is already
  loading, and each refetch would be stale before it landed. `useCoalescedCallback` collapses a
  window into one trailing-edge refetch, so a thousand events cost one extra request.
- **Status names are mapped for display.** The backend keeps granular names because an operator
  needs to know which stage failed; `features/orders/status.ts` is the single table that turns
  them into customer-readable labels, with a test that covers the whole enum so a new backend
  status fails a test rather than rendering blank.

### Redrive vs cancel — two failures, two remedies

These are not interchangeable, and which one applies is decided by *how* the order failed.

```
transient fault, retries exhausted          permanent failure
  worker kept throwing                        worker declined the order
        │                                            │
        ▼                                            ▼
  message → DLQ                              FAILED → NEEDS_MANUAL_REVIEW
  order parked mid-flight                    message deleted on the success path
  (e.g. PAYMENT_PROCESSING)                  (it never reaches the DLQ)
        │                                            │
        ▼                                            ▼
  REDRIVE — worker resumes                   CANCEL — release the reservation
  from where it stopped                      back to the catalog
```

A permanently failing order never reaches the DLQ at all: the worker records the failure,
routes it to manual review and deletes the message normally. So everything in the DLQ is work
that was *interrupted*, and `OrderFulfillmentService` being resume-aware is what makes putting
it back meaningful — it picks up at the current status rather than restarting.

Verified end to end against LocalStack: three orders left stuck in `PAYMENT_PROCESSING` by a
worker throwing transient faults, their messages dead-lettered after `maxReceiveCount`, then a
redrive with a healthy worker took all three to `FULFILLED`. A second redrive request while the
first was in flight returned 409 with its progress. Messages whose order does not exist cycle
back to the DLQ rather than disappearing, which is the correct outcome — they are not
recoverable.

Redrives are operator-triggered and rate-limited (`ops.redrive.max-messages-per-second`,
default 10), never automatic. An automatic redrive would loop a permanently failing message
between the two queues forever, which is the failure mode a DLQ exists to prevent.

### Dashboard design notes

- **Headline numbers are stat tiles, not a chart.** Four aggregates an operator acts on
  (in fulfillment, fulfilled, needs attention, cancelled). A grouped bar chart of four numbers
  is a chart doing a tile's job.
- **The nine per-status counts are a table.** Past roughly seven classes that all carry
  meaning, more colour stops helping — so the breakdown is a table, and each row deep-links to
  that filter on the order list.
- **Queue depth is a meter, not a chart.** Each row is one value against one limit. The fill
  carries severity and the track is a lighter step of the same ramp, so state reads across the
  whole bar rather than only where the fill ends. The DLQ meter is full at one message, because
  the tolerance is zero rather than some scaled allowance.
- **Values wear text ink; colour lives on marks.** A tile's number is primary ink with a small
  coloured dot beside it. Tinting the number would put meaning in colour alone and drag small
  text onto hues chosen for a 3:1 mark contrast rather than AA body text.
- **Status colour is never the only signal.** Every badge, warning and meter carries a text
  label. This matters concretely here: amber and red sit close together under deuteranopia
  (ΔE 4.4), and no amber that clears AA *text* contrast on white keeps them apart — the
  darker candidates drop normal-vision separation below the ΔE 15 floor. The label is the
  mitigation, which is also why `--warning` is used for marks and not for small text.
- **Proportional figures on tile values, `tabular-nums` only in table columns.** Equal-width
  digits make a value like `121` read gappy at display size; they earn their place where
  columns of numbers must align vertically.

### Health checks

```
GET /actuator/health           full view, including redis
GET /actuator/health/serving   what the ECS container health check probes
```

`serving` deliberately excludes Redis. Redis is degradable — the idempotency fast path falls
back to DynamoDB and the event bridge reconnects on its own — so if the restart-triggering
probe failed on it, ECS would kill and replace every API task while Redis was down and turn a
partial degradation into a total outage. The trade-off is a weaker probe that reports process
liveness rather than end-to-end readiness, which is the right side to err on for a check that
can restart tasks. The full `/actuator/health` still reports `redis: DOWN` for dashboards.

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

## Benchmarks

Full results, methodology and caveats: [`load-tests/results/README.md`](load-tests/results/README.md).
Every number below comes from a run whose raw output is committed there.

All figures are from a **single host** — API and worker as separate JVMs, with DynamoDB Local,
LocalStack and Redis in Docker. They establish that the design behaves correctly under
concurrency and that the real-time path is not the bottleneck; they are not capacity planning
for a deployed system.

### Real-time event delivery

`order state committed to DynamoDB → event received by a subscribed client`

| Clients | Established | Dropped | Events | Throughput | p50 | p95 | p99 |
|--------:|------------:|--------:|-------:|-----------:|----:|----:|----:|
| 100  | 100/100   | 0 | 10,000  | 1,319/s  | 3 ms  | 7 ms  | 15 ms |
| 250  | 250/250   | 0 | 25,000  | 3,183/s  | 4 ms  | 14 ms | 30 ms |
| 500  | 500/500   | 0 | 50,000  | 6,627/s  | 5 ms  | 14 ms | 21 ms |
| 1000 | 1000/1000 | 0 | 100,000 | 12,972/s | 10 ms | 40 ms | 77 ms |

```bash
make ws-latency-suite    # the whole ladder
make ws-latency-test CLIENTS=500
```

**Read the high-count figures as a range.** Three runs of the identical 1000-client
configuration gave p95 of 40 ms, 26 ms and 51 ms — the harness holds all 1000 connections in one
Node process and competes for cores with the very JVMs it is measuring, so this is the whole
system co-located on one laptop rather than the server measured in isolation. p50 is stable;
p95/p99 at the top two levels are not. What holds across every run is that p95 stayed under
60 ms and no connection dropped.

Two other things the measurement depends on: the harness runs on the same host as the API
because each sample is a subtraction against a shared clock, and warmup samples are discarded
because the first event through a cold JIT measures ~180 ms against a 2–3 ms steady state. Full
methodology and the failed attempt to separate harness cost from server cost are in the
[results README](load-tests/results/README.md).

### Concurrent checkout — zero oversell

100 units of stock, 1000 simultaneous checkout attempts:

```
checkout_success   100      PASS
checkout_rejected  900      PASS
http_req_failed    0.00%    PASS

final inventory:   availableQuantity=0  reservedQuantity=100  version=100
```

`version=100` is the assertion that matters. The version counter increments once per successful
conditional write, so landing on exactly 100 proves exactly 100 reservations committed against
100 units — no oversell, and no lost update.

```bash
make load-test
```

### Order-creation latency

Concurrency is part of the result, not a footnote — the same endpoint differs by 7x across
these two runs:

| Sustained VUs | Throughput | avg | p95 | p99 | Errors |
|--------------:|-----------:|----:|----:|----:|-------:|
| 20  | 249 orders/s | 64 ms  | **112 ms** | 201 ms  | 0% |
| 200 | 373 orders/s | 430 ms | **827 ms** | 1.46 s  | 0% |

```bash
make throughput-test              # 200 VUs
make throughput-test VUS=20
```

An earlier pre-upgrade run measured p95 67 ms at 20 VUs. The current 112 ms is the cost of
snapshotting catalog prices at checkout, which adds a `BatchGetItem` to the hot path before the
transaction. That is a deliberate trade — it is what stops a later price change from rewriting
the value of an existing order — but it means the older figure should not be quoted for the
current code.

### Reliability

```bash
make reliability-test    # duplicate checkout, DLQ isolation, dead-letter recovery
make failure-test        # failure-injection smoke: only 201/409, no 5xx
```

| Scenario | Result |
|---|---|
| Duplicate checkout | 100/100 duplicates returned the original `orderId`; inventory deducted once per key |
| Permanent failure → DLQ | 10/10 poison messages isolated in the DLQ, source queue drained |
| Dead-letter recovery | 5 orders parked mid-flight by exhausted retries all resumed to `FULFILLED` after redrive; a concurrent redrive was rejected with 409 |

These need an isolated queue and order table — see the results README for why a shared local
stack carrying state from a throughput run makes the counts unattributable.

## AWS Deployment

```
                        ┌─────────────────────────┐
   browser ────────────▶│      CloudFront         │
                        │  (single origin)        │
                        └────┬───────────────┬────┘
                    /*       │               │  /api/*  ·  /ws
                             ▼               ▼
                    ┌────────────────┐  ┌──────────────┐
                    │  S3 (SPA)      │  │     ALB      │
                    └────────────────┘  └──────┬───────┘
                                               ▼
                                     ┌──────────────────┐
                                     │ orderops-api     │  ECS/Fargate
                                     └──────────────────┘
                                     ┌──────────────────┐
                                     │ orderops-worker  │  ECS/Fargate
                                     └──────────────────┘
                                        │      │      │
                                   DynamoDB   SQS  ElastiCache
```

The React app is static files on S3, not a container. Running a Fargate task to serve them
would pay for an always-on container, an ALB target and a health check to do a job object
storage does better. The image in `apps/web/Dockerfile` still exists — it is what
`make app-up` uses to run the whole stack locally — it just is not what production serves.

CloudFront carries **two origins**, so `/api/*` and `/ws` reach the ALB through the same
distribution the app is served from. That is what keeps the browser on one origin, which is why
the app's fetch and WebSocket URLs are relative and CORS is off the critical path. The API's
CORS config remains for the split-origin case: the Vite dev server, or an API on its own host.

### One-time setup

```bash
# 1. ECR, ECS cluster, log group, DynamoDB tables, SQS queues, IAM task role
bash infra/ecs/setup.sh

# 2. ElastiCache endpoint (the app reads it from SSM, never from an env var in the task def)
aws ssm put-parameter --name /orderops/redis-host \
  --value <elasticache-endpoint> --type SecureString

# 3. Create the ECS services and the ALB, then:
ALB_DNS=<alb-dns-name> bash infra/frontend/setup.sh    # S3 bucket + CloudFront distribution

# 4. Alarms (ALB ids are optional; without them the ALB alarms are skipped)
ALB_ID=app/orderops-alb/xxx TARGET_GROUP_ID=targetgroup/orderops-api/xxx \
  bash infra/ecs/cloudwatch-alarms.sh

# 5. Verify against the ALB directly, not CloudFront
BASE_URL=http://<alb-dns-name> bash infra/smoke-test.sh
```

The task role is scoped to the four DynamoDB tables, the two queues and the app's own metrics
namespace, rather than the managed `AmazonDynamoDBFullAccess` / `AmazonSQSFullAccess` policies —
a leaked task credential should not be able to read or drop anything else in the account.

### CI/CD

Every push and pull request runs four jobs:

| Job | What it does |
|---|---|
| `backend` | `mvn test` — unit plus DynamoDB Local integration tests |
| `frontend` | `npm ci`, lint, typecheck, test, build |
| `docker` | builds both images without pushing, so a broken Dockerfile fails the PR |
| `deploy` | manual only — see below |

Deployment is **`workflow_dispatch`, not automatic on `main`**. This repository deploys to a
personal AWS account where an accidental deploy costs real money and can break the environment
while nobody is watching. The gate is deliberate rather than missing: every check above runs on
each push, so `main` is always known-good and deployable on demand.

The deploy job, once triggered:

1. Builds and pushes **one** image to ECR — the API and worker are the same jar behind
   `APP_MODE`, so building twice would only create a chance for them to diverge
2. Rolling-deploys both ECS services with `wait-for-service-stability`, so a task that starts
   and immediately fails its health check fails the step instead of being reported as success
3. Builds the SPA and syncs it to S3 — **hashed assets first** with a one-year `max-age`, then
   `index.html` with `no-cache`. In that order a client fetching the new `index.html` always
   finds the assets it names; the reverse order leaves a window serving an index pointing at
   objects that do not exist yet
4. Invalidates the CloudFront cache for `/` and `/index.html`
5. Runs `infra/smoke-test.sh` against the ALB

Required GitHub configuration:

| Kind | Name | Value |
|---|---|---|
| Secret | `AWS_ROLE_ARN` | IAM role for OIDC, with ECR push, ECS deploy, S3 and CloudFront permissions |
| Variable | `FRONTEND_BUCKET` | S3 bucket name, printed by `infra/frontend/setup.sh` |
| Variable | `CLOUDFRONT_DISTRIBUTION` | Distribution ID, printed by the same script |
| Variable | `ALB_BASE_URL` | e.g. `http://orderops-alb-xxx.us-west-2.elb.amazonaws.com` |

### Smoke test

`infra/smoke-test.sh` creates one real order, which is the only way to know the DynamoDB
transaction, the SQS publish and the worker are all pointed at the same resources. It checks:

1. `/actuator/health/serving` responds
2. Inventory seeding works (DynamoDB writable)
3. An order is created (transaction + SQS publish)
4. A replay with the same `Idempotency-Key` returns the original order
5. `/ops/queue-health` reports SQS **readable** — a deploy whose task role cannot read SQS still
   serves orders, so this would not show up as an unhealthy target and has to be asserted
6. The order reaches a terminal status within a minute, proving the worker task is alive and
   consuming from the same queue

It runs against the ALB rather than CloudFront on purpose: a cached response could make a dead
backend look alive, which is exactly what the step exists to catch.

### CloudWatch

The app publishes its own Micrometer meters through `micrometer-registry-cloudwatch2` into the
`OrderOps` namespace, enabled by `CLOUDWATCH_METRICS_ENABLED` in the task definitions and off
everywhere else. Without a registry those counters never leave the JVM, so alarming on
"fulfillment failures" or "real-time delivery" would have had nothing to alarm on. Every metric
is tagged with `app-mode`, so an alarm can target the API's WebSocket gauge without the
worker's zero pulling the average down.

```bash
bash infra/ecs/cloudwatch-alarms.sh
```

| Alarm | Signal |
|---|---|
| `orderops-{api,worker}-cpu-high` | CPU > 80% |
| `orderops-{api,worker}-no-tasks` | running task count < 1 |
| `orderops-queue-backlog` | main queue depth > 100 |
| `orderops-queue-message-age` | oldest message > 5 min |
| `orderops-dlq-not-empty` | any message in the DLQ |
| `orderops-fulfillment-failures` | transient failure counter |
| `orderops-orders-needing-review` | orders given up on |
| `orderops-realtime-publish-failures` | event publishing broken |
| `orderops-api-5xx` | ALB target 5xx |
| `orderops-api-latency-p95` | ALB p95 > 2 s |
| `orderops-api-unhealthy-targets` | any unhealthy target |

Queue **depth** and **age** are both alarmed because either alone misses a real outage: a deep
queue that is draining is a capacity signal, while an old message means consumption has stalled
regardless of depth.

`ApproximateAgeOfOldestMessage` lives here rather than in the `/ops/queue-health` API because it
is a CloudWatch metric, not an SQS queue attribute — reading it from SQS would mean receiving a
message, which advances its receive count and pushes it toward the DLQ.

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

