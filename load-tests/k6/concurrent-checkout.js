/**
 * Concurrent Checkout Load Test
 *
 * Verifies zero oversell under maximum concurrency:
 * - TOTAL_REQUESTS virtual users each attempt to reserve 1 unit
 * - Inventory is pre-seeded to INITIAL_STOCK units
 * - Expected: exactly INITIAL_STOCK succeed (201), the rest are rejected (409)
 *
 * The response-count thresholds are necessary but not sufficient: they prove the API *replied*
 * the right number of times, not that the inventory record ended up consistent. A lost update —
 * two concurrent writes reading the same stale value, each decrementing, one overwriting the
 * other — can produce a correct count of 201s alongside a wrong final quantity. So teardown
 * reads the item back and asserts the end state, including the version counter.
 *
 * Run:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=load-test-item load-tests/k6/concurrent-checkout.js
 *
 * Override the scenario with -e INITIAL_STOCK=500 -e TOTAL_REQUESTS=1000 (seed to match).
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Gauge } from 'k6/metrics';

const successCount  = new Counter('checkout_success');
const rejectedCount = new Counter('checkout_rejected');

// Recorded once in teardown. Gauges rather than counters: these are a final reading of one
// record, not something accumulated across iterations.
const finalAvailable = new Gauge('final_available_quantity');
const finalReserved  = new Gauge('final_reserved_quantity');
const finalVersion   = new Gauge('final_inventory_version');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID  = __ENV.ITEM_ID  || 'load-test-item';

const INITIAL_STOCK   = Number(__ENV.INITIAL_STOCK   || 100);
const TOTAL_REQUESTS  = Number(__ENV.TOTAL_REQUESTS  || 1000);
const EXPECTED_REJECTED = TOTAL_REQUESTS - INITIAL_STOCK;

// Tell k6 to treat 2xx and 4xx as expected responses (409 = correct inventory rejection)
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  vus: TOTAL_REQUESTS,
  iterations: TOTAL_REQUESTS,
  thresholds: {
    // Zero oversell, as seen from the API: exactly INITIAL_STOCK orders created.
    'checkout_success':  [`count == ${INITIAL_STOCK}`],
    'checkout_rejected': [`count == ${EXPECTED_REJECTED}`],
    // No network errors or 5xx
    'http_req_failed':   ['rate == 0'],

    // Zero oversell, as seen from the database. All three are asserted because each rules out
    // a different way the invariant could break:
    //   available == 0            stock was fully consumed and never went negative
    //   reserved  == INITIAL_STOCK  every consumed unit is accounted for as reserved
    //   version   == INITIAL_STOCK  exactly that many conditional writes committed — this is
    //                               the one that rules out a lost update, since the version
    //                               counter increments once per successful conditional write
    //
    // Note the set is also fail-safe if teardown never runs: an unobserved gauge reports 0, so
    // 'available == 0' would pass on its own. The two non-zero expectations below cannot, which
    // is why asserting all three matters rather than just the obvious one.
    'final_available_quantity': ['value == 0'],
    'final_reserved_quantity':  [`value == ${INITIAL_STOCK}`],
    'final_inventory_version':  [`value == ${INITIAL_STOCK}`],

    // A liveness bound, not a performance claim. This test exists to prove the oversell
    // invariant, and at 1000 simultaneous VUs against a single local process the latency
    // distribution is a property of the load generator and the host, not of correctness — a
    // tight bound here fails on a busy machine while zero-oversell is perfectly intact.
    // Order-creation latency is measured at a stated concurrency by throughput.js instead.
    'http_req_duration': ['p(99) < 15000'],
  },
};

/**
 * Fails fast if the item was not seeded to the stock this run asserts against, rather than
 * letting every threshold fail and leaving the cause to be guessed from the counts.
 */
export function setup() {
  const res = http.get(`${BASE_URL}/api/v1/inventory/${ITEM_ID}`);
  if (res.status !== 200) {
    throw new Error(
      `${ITEM_ID} is not seeded (GET returned ${res.status}). ` +
      `Seed it first: bash load-tests/scripts/seed.sh ${BASE_URL} ${ITEM_ID} ${INITIAL_STOCK} 19.99`,
    );
  }
  const inv = res.json();
  if (inv.availableQuantity !== INITIAL_STOCK) {
    throw new Error(
      `${ITEM_ID} has availableQuantity=${inv.availableQuantity}, expected ${INITIAL_STOCK}. ` +
      `Re-seed before running, or set -e INITIAL_STOCK=${inv.availableQuantity}.`,
    );
  }
  return { startingVersion: inv.version };
}

export default function () {
  const payload = JSON.stringify({
    customerId: `load-customer-${__VU}`,
    items: [{ itemId: ITEM_ID, quantity: 1 }],
  });

  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  if (res.status === 201) {
    successCount.add(1);
    check(res, { 'order created (201)': (r) => r.status === 201 });
  } else if (res.status === 409) {
    rejectedCount.add(1);
    check(res, { 'inventory rejected (409)': (r) => r.status === 409 });
  } else {
    check(res, { 'unexpected status': () => false });
  }
}

/**
 * Reads the contended record back once every VU has finished, and records the end state as
 * metrics so the thresholds above decide pass/fail. Recording metrics rather than calling
 * check() here is deliberate: a failed check does not fail a k6 run, a breached threshold does.
 *
 * @param data value returned by setup(); carries the item's version before the run
 */
export function teardown(data) {
  const res = http.get(`${BASE_URL}/api/v1/inventory/${ITEM_ID}`);
  if (res.status !== 200) {
    throw new Error(`teardown could not read ${ITEM_ID}: HTTP ${res.status}`);
  }

  const inv = res.json();
  finalAvailable.add(inv.availableQuantity);
  finalReserved.add(inv.reservedQuantity);
  // Measured as a delta: seeding writes the item with version 0, but a re-seeded item carries
  // whatever version it had, so the absolute value is only meaningful relative to the start.
  finalVersion.add(inv.version - data.startingVersion);

  console.log(
    `\n  final inventory state for ${ITEM_ID}:\n` +
    `    availableQuantity  ${inv.availableQuantity}  (expected 0)\n` +
    `    reservedQuantity   ${inv.reservedQuantity}  (expected ${INITIAL_STOCK})\n` +
    `    version            +${inv.version - data.startingVersion}  ` +
    `(expected +${INITIAL_STOCK} — one per committed conditional write)\n`,
  );
}
