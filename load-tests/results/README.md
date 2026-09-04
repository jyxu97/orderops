# Benchmark results

Raw output from the benchmark and reliability runs, kept in the repo so every number quoted in
the README is traceable to the run that produced it.

## How these were produced

| File | Produced by |
|---|---|
| `ws-latency-*.json` / `-samples.csv` | `load-tests/ws/latency-benchmark.mjs` |
| `concurrent-checkout-1k.json` | `k6 run load-tests/k6/concurrent-checkout.js` |
| `throughput-200vu.json` | `k6 run load-tests/k6/throughput.js` |

Reproduce with `make ws-latency-test`, `make load-test` and `make throughput-test`.

## Environment

Every figure below comes from a **single host** — an 8-core Apple Silicon laptop running the API
and worker as separate JVM processes, with DynamoDB Local, LocalStack (SQS) and Redis in Docker.
Nothing crossed a real network, and there was no AWS hop.

It is a development machine, not an isolated benchmark rig: other projects' containers and
services come and go on it, and one of them seized ports 8080 and 6379 partway through a
session. Anything measured here carries that caveat.

These are therefore *shape* numbers, not capacity planning for a deployed system. What they
establish is that the design behaves correctly under concurrency and that the real-time path is
not the bottleneck — not what a Fargate task will do.

## Methodology notes that affect how the numbers should be read

**Latency is measured commit → receive.** The event payload carries `committedAtEpochMilli`,
stamped by whichever process committed the DynamoDB write, and the client subtracts it from its
own clock on arrival. That subtraction is only meaningful while both share a clock, which is why
the harness must run on the same host as the API. A cross-host run would be measuring clock
skew as much as delivery.

**Warmup samples are discarded.** The first event through a cold JIT and a freshly-subscribed
Redis listener measures around 180 ms against a steady state of 2–3 ms. Blending that in would
make p99 a measure of startup rather than of delivery, so the harness runs a warmup phase whose
samples are thrown away and says so in its output.

**High client counts are contention-bound, and repeat runs vary by about 2x.** The harness holds
all N connections in one Node process, so its own event-loop queuing sits *inside* the measured
interval — and on an 8-core laptop that process competes with the JVMs and the Docker
containers it is measuring.

Three runs of the identical 1000-client configuration produced p95 of **40 ms, 26 ms and
51 ms**. The table below records one run per level; treat the high-count figures as a range,
not a reproducible point value.

An attempt to separate harness cost from server cost — splitting the same 1000 connections
across five Node processes so each parsed a fifth of the frames — made p95 *worse* (44–49 ms),
because five processes contend for the same cores. So on this hardware the two cannot be
cleanly separated: what is being measured is the whole system co-located on one machine, not
the server's capability in isolation.

Two consequences worth being explicit about:

- **Do not quote a precise figure from these runs.** A claim that survives the observed spread
  (e.g. "under 100 ms p95 at 1K concurrent subscribers") is defensible; "40 ms" is not.
- **A deployed measurement would differ in both directions.** On AWS the server no longer shares
  cores with its own load generator, and a real browser holds one connection rather than a
  thousand — both push latency down. Against that, a real client adds CloudFront/ALB round-trip
  time, an ElastiCache network hop and possibly a slower vCPU. These runs cannot predict the
  net, only bound the server-side work. Attributing a latency number to the AWS deployment
  requires measuring it there, with the harness inside the same VPC so the timestamp
  subtraction is still against a shared clock.

**Reliability tests need an isolated queue and a clean order table.** Both the DLQ and the
redrive scripts purge the source queue as well as the DLQ, and the redrive script tracks its own
order IDs rather than counting a status globally. Without that, a backlog or parked orders from
an earlier run get counted as this run's, and the assertions stop meaning anything — which is
how the first DLQ run reported 41 dead-lettered messages against an expected 10.

## WebSocket event-delivery latency

20 orders per run, ~5 events per order, every client subscribed to `/topic/ops/orders` so each
event fans out to all of them.

| Clients | Established | Unexpected disconnects | Events | Throughput | p50 | p95 | p99 | max | JVM heap |
|--------:|------------:|-----------------------:|-------:|-----------:|----:|----:|----:|----:|---------:|
| 100  | 100/100   | 0 | 10,000  | 1,319/s  | 3 ms  | 7 ms  | 15 ms | 17 ms | 172 MB |
| 250  | 250/250   | 0 | 25,000  | 3,183/s  | 4 ms  | 14 ms | 30 ms | 35 ms | 164 MB |
| 500  | 500/500   | 0 | 50,000  | 6,627/s  | 5 ms  | 14 ms | 21 ms | 34 ms | 213 MB |
| 1000 | 1000/1000 | 0 | 100,000 | 12,972/s | 10 ms | 40 ms | 77 ms | 89 ms | 291 MB |

Every connection was established at every level and none dropped, at all four levels.

The p50 figures are stable across repeats; the p95/p99 figures at 500 and 1000 clients are not
— see the variance note above. Across every 1000-client run observed, p95 stayed under 60 ms.

## Concurrent checkout — the oversell invariant

100 units of stock, 1000 simultaneous checkout attempts of 1 unit each.

```
checkout_success   100      (threshold: count == 100)   PASS
checkout_rejected  900      (threshold: count == 900)   PASS
http_req_failed    0.00%    (threshold: rate == 0)      PASS

final inventory:   availableQuantity=0  reservedQuantity=100  version=100
```

`version=100` is the load-bearing assertion: the item's version counter increments once per
successful conditional write, so it having landed on exactly 100 means exactly 100 reservations
committed against 100 units — no oversell, and no lost update either.

Latency is deliberately *not* claimed from this run. Its threshold is a liveness bound, because
at 1000 simultaneous VUs against one local process the distribution is a property of the load
generator and the host rather than of correctness.

## Order-creation latency — sustained load

Ramped over 30 s, held for 60 s at the stated concurrency.

| Sustained VUs | Orders | Throughput | avg | p95 | p99 | max | Errors |
|--------------:|-------:|-----------:|----:|----:|----:|----:|-------:|
| 20  | 24,942 | 249/s | 64 ms  | 112 ms | 201 ms | 723 ms | 0% |
| 200 | 37,292 | 373/s | 430 ms | 827 ms | 1.46 s | 2.01 s | 0% |

Any latency figure taken from here has to carry its concurrency — the same endpoint is 7x apart
across these two rows, so a number on its own is not a claim about anything.

### Regression against the pre-upgrade figure

An earlier run of this test, before the full-stack upgrade, recorded **p95 67 ms at 20 VUs**.
The current 20-VU run is **112 ms**.

The difference is the price snapshot added at checkout: `createOrder` now issues a
`BatchGetItem` against the inventory table before opening its transaction, to capture the unit
price each line item is being charged. That read is what stops a later catalog price change from
rewriting the value of an existing order, and it also lets an unknown SKU fail fast with a 404
instead of being misreported as insufficient stock.

It is a deliberate trade, but it does mean the older 67 ms figure no longer describes this code
and should not be quoted for it.

## Reliability

| Scenario | Script | Result |
|---|---|---|
| Duplicate checkout | `idempotency-test.sh` | 100/100 duplicates returned the original `orderId`; inventory deducted exactly once per key (10 of 10) |
| Permanent failure → DLQ | `dlq-isolation-test.sh` | 10/10 poison messages routed to the DLQ, source queue drained |
| Dead-letter recovery | `redrive-recovery-test.sh` | 5 orders parked in `PAYMENT_PROCESSING` by exhausted retries, all 5 resumed to `FULFILLED` after redrive; concurrent redrive request correctly rejected with 409 |
