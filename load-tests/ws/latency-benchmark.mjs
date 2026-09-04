/**
 * WebSocket event-delivery latency benchmark.
 *
 * Measures the interval that matters for the product claim:
 *
 *     order state committed to DynamoDB  →  event received by a browser
 *
 * The event payload carries `committedAtEpochMilli`, stamped by the process that committed the
 * write, so each sample is one subtraction at the receiver. That is only valid while publisher
 * and subscriber share a clock, which is why this harness is meant to run on the same host as
 * the API — a cross-host run would be measuring clock skew as much as latency.
 *
 * The measured path is the real one: DynamoDB commit → Redis publish (from the worker process)
 * → API receives → STOMP broadcast → this client. Nothing is stubbed.
 *
 * Usage:
 *   node latency-benchmark.mjs --clients 500 --orders 40 [--items 2] [--warmup 5]
 *                              [--api http://localhost:8080] [--out ../results]
 */
import { writeFileSync } from 'node:fs';
import { StompClient } from './stomp.mjs';

// ── Arguments ───────────────────────────────────────────────────────────────

function args() {
  const parsed = {
    clients: 100,
    orders: 30,
    items: 1,
    warmup: 5,
    api: 'http://localhost:8080',
    origin: 'http://localhost:5173',
    out: null,
    label: null,
    // Listen without driving any orders. Lets several harness processes share one connection
    // pool so client-side event-loop queuing can be separated from server-side fan-out cost.
    listenSeconds: 0,
  };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i]?.replace(/^--/, '');
    const value = argv[i + 1];
    if (key && value !== undefined && key in parsed) {
      parsed[key] = ['clients', 'orders', 'items', 'warmup', 'listenSeconds'].includes(key)
        ? Number(value)
        : value;
    }
  }
  return parsed;
}

const OPTS = args();
const WS_URL = OPTS.api.replace(/^http/, 'ws') + '/ws';
const ITEM_ID = `ws-bench-${Date.now()}`;

// ── Stats ───────────────────────────────────────────────────────────────────

/**
 * Nearest-rank percentile on a sorted array — no interpolation, so every reported figure is an
 * observed sample rather than a number that never occurred.
 */
function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  const rank = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.min(Math.max(rank, 0), sorted.length - 1)];
}

function summarise(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  return {
    count: sorted.length,
    min: sorted[0] ?? null,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1] ?? null,
    mean: sorted.length ? Number((sorted.reduce((a, b) => a + b, 0) / sorted.length).toFixed(2)) : null,
  };
}

// ── API helpers ─────────────────────────────────────────────────────────────

async function api(path, init) {
  const response = await fetch(`${OPTS.api}${path}`, init);
  if (!response.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} → ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

async function seedInventory(quantity) {
  await api('/api/v1/inventory/seed', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ itemId: ITEM_ID, itemName: 'WS Benchmark Item', quantity, unitPrice: 1.0 }),
  });
}

function placeOrder(customerId) {
  return api('/api/v1/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ customerId, items: [{ itemId: ITEM_ID, quantity: OPTS.items }] }),
  });
}

async function serverMetric(name) {
  try {
    const body = await api(`/actuator/metrics/${name}`);
    return body?.measurements?.[0]?.value ?? null;
  } catch {
    return null;
  }
}

// ── Run ─────────────────────────────────────────────────────────────────────

const samples = [];
let collecting = false;
let eventsReceived = 0;
let malformed = 0;
let unexpectedCloses = 0;
let connectFailures = 0;

function onEvent(event) {
  if (event === null) {
    malformed += 1;
    return;
  }
  eventsReceived += 1;
  // Warmup samples are discarded rather than blended in: the first event through a cold JIT
  // and a freshly-subscribed Redis listener is ~100x the steady-state figure, and letting that
  // sit in the distribution would make p99 a measure of startup, not of delivery.
  if (collecting && typeof event.committedAtEpochMilli === 'number') {
    samples.push(Date.now() - event.committedAtEpochMilli);
  }
}

async function openClients(count) {
  const clients = [];
  // Connect in batches: a thousand simultaneous handshakes queue behind each other and the
  // later ones time out, which would look like a server limit rather than a harness artifact.
  const BATCH = 50;
  for (let i = 0; i < count; i += BATCH) {
    const batch = Array.from({ length: Math.min(BATCH, count - i) }, (_, n) => {
      const client = new StompClient({
        url: WS_URL,
        origin: OPTS.origin,
        onEvent,
        onClose: (code) => {
          if (code !== 1000 && code !== 1001) unexpectedCloses += 1;
        },
      });
      return client
        .connect()
        .then((connected) => {
          connected.subscribe(`sub-${i + n}`, '/topic/ops/orders');
          return connected;
        })
        .catch(() => {
          connectFailures += 1;
          return null;
        });
    });
    clients.push(...(await Promise.all(batch)));
  }
  return clients.filter(Boolean);
}

/** Places orders one at a time so the measurement is delivery latency, not queue backlog. */
async function driveOrders(count, prefix) {
  for (let i = 0; i < count; i++) {
    try {
      await placeOrder(`${prefix}-${i}`);
    } catch (error) {
      console.error(`  order ${i} failed: ${error.message}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
}

/** Waits for the event stream to go quiet, so late arrivals are counted rather than cut off. */
async function drain(quietMs = 3000, maxWaitMs = 30000) {
  const start = Date.now();
  let lastSeen = eventsReceived;
  let quietSince = Date.now();
  while (Date.now() - start < maxWaitMs) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    if (eventsReceived !== lastSeen) {
      lastSeen = eventsReceived;
      quietSince = Date.now();
    } else if (Date.now() - quietSince >= quietMs) {
      return;
    }
  }
}

async function main() {
  console.log(`OrderOps WebSocket delivery latency`);
  console.log(`  api            ${OPTS.api}`);
  console.log(`  clients        ${OPTS.clients}`);
  console.log(`  orders         ${OPTS.orders} (+${OPTS.warmup} warmup, discarded)`);
  console.log('');

  await seedInventory((OPTS.orders + OPTS.warmup + 5) * OPTS.items);

  process.stdout.write(`connecting ${OPTS.clients} client(s)… `);
  const clients = await openClients(OPTS.clients);
  // The broker registers subscriptions asynchronously; events published before they land are
  // simply not delivered and would understate the event count.
  await new Promise((resolve) => setTimeout(resolve, 1500));
  const activeOnServer = await serverMetric('realtime.connections.active');
  console.log(`${clients.length} connected (server reports ${activeOnServer ?? 'n/a'})`);

  // Listener mode: another process is driving the orders; just collect what arrives.
  if (OPTS.listenSeconds > 0) {
    collecting = true;
    process.stdout.write(`listening for ${OPTS.listenSeconds}s… `);
    const started = Date.now();
    await new Promise((resolve) => setTimeout(resolve, OPTS.listenSeconds * 1000));
    collecting = false;
    const elapsed = Date.now() - started;
    const stats = summarise(samples);
    console.log(`${samples.length} sample(s)`);
    console.log(`  p50 ${stats.p50} ms · p95 ${stats.p95} ms · p99 ${stats.p99} ms · ` +
      `${eventsReceived} events (${(eventsReceived / (elapsed / 1000)).toFixed(0)}/s) · ` +
      `${unexpectedCloses} disconnect(s)`);
    if (OPTS.out) {
      writeFileSync(`${OPTS.out}/ws-latency-${OPTS.label ?? 'listener'}.json`,
        JSON.stringify({ label: OPTS.label, clients: clients.length, latencyMs: stats,
          eventsReceived, unexpectedCloses }, null, 2) + '\n');
    }
    clients.forEach((client) => client.close());
    process.exit(samples.length > 0 ? 0 : 1);
  }

  if (OPTS.warmup > 0) {
    process.stdout.write(`warming up with ${OPTS.warmup} order(s)… `);
    await driveOrders(OPTS.warmup, 'warmup');
    await drain(1500, 15000);
    console.log(`${eventsReceived} event(s) seen, discarded`);
  }

  eventsReceived = 0;
  collecting = true;
  process.stdout.write(`measuring ${OPTS.orders} order(s)… `);
  const startedAt = Date.now();
  await driveOrders(OPTS.orders, 'bench');
  await drain();
  const elapsedMs = Date.now() - startedAt;
  collecting = false;
  console.log(`${samples.length} sample(s)`);

  const heapBytes = await serverMetric('jvm.memory.used');
  const activeAtEnd = await serverMetric('realtime.connections.active');

  const summary = {
    label: OPTS.label ?? `${OPTS.clients}-clients`,
    recordedAt: new Date().toISOString(),
    config: {
      clients: OPTS.clients,
      orders: OPTS.orders,
      itemsPerOrder: OPTS.items,
      warmupOrders: OPTS.warmup,
      api: OPTS.api,
    },
    connections: {
      requested: OPTS.clients,
      established: clients.length,
      connectFailures,
      unexpectedCloses,
      serverReportedAtStart: activeOnServer,
      serverReportedAtEnd: activeAtEnd,
    },
    delivery: {
      eventsReceived,
      malformed,
      durationMs: elapsedMs,
      eventsPerSecond: Number((eventsReceived / (elapsedMs / 1000)).toFixed(1)),
    },
    latencyMs: summarise(samples),
    server: {
      jvmMemoryUsedMb: heapBytes ? Number((heapBytes / 1024 / 1024).toFixed(1)) : null,
    },
  };

  console.log('');
  console.log(`  connections established   ${summary.connections.established}/${OPTS.clients}` +
    (connectFailures ? `  (${connectFailures} failed)` : ''));
  console.log(`  unexpected disconnects    ${unexpectedCloses}`);
  console.log(`  events delivered          ${eventsReceived}  (${summary.delivery.eventsPerSecond}/s)`);
  console.log(`  latency  p50              ${summary.latencyMs.p50} ms`);
  console.log(`           p95              ${summary.latencyMs.p95} ms`);
  console.log(`           p99              ${summary.latencyMs.p99} ms`);
  console.log(`           max              ${summary.latencyMs.max} ms`);
  console.log(`  JVM heap used             ${summary.server.jvmMemoryUsedMb} MB`);

  if (OPTS.out) {
    const stem = `${OPTS.out}/ws-latency-${summary.label}`;
    writeFileSync(`${stem}.json`, JSON.stringify(summary, null, 2) + '\n');
    // Raw samples too: a summary alone cannot be re-analysed or re-percentiled later.
    writeFileSync(`${stem}-samples.csv`, 'latency_ms\n' + samples.join('\n') + '\n');
    console.log(`\n  wrote ${stem}.json and -samples.csv`);
  }

  clients.forEach((client) => client.close());
  // Non-zero exit if nothing was measured, so a broken run cannot be mistaken for a good one.
  process.exit(samples.length > 0 ? 0 : 1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
