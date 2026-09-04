/**
 * Concurrent Checkout Load Test
 *
 * Verifies zero oversell under maximum concurrency:
 * - 1000 virtual users each attempt to reserve 1 unit
 * - Inventory is pre-seeded to 100 units
 * - Expected: exactly 100 succeed (201), 900 are rejected (409)
 *
 * Run:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=load-test-item load-tests/k6/concurrent-checkout.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const successCount  = new Counter('checkout_success');
const rejectedCount = new Counter('checkout_rejected');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID  = __ENV.ITEM_ID  || 'load-test-item';

// Tell k6 to treat 2xx and 4xx as expected responses (409 = correct inventory rejection)
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  vus: 1000,
  iterations: 1000,
  thresholds: {
    // Zero oversell: exactly 100 orders created against 100-unit stock
    'checkout_success':  ['count == 100'],
    'checkout_rejected': ['count == 900'],
    // No network errors or 5xx
    'http_req_failed':   ['rate == 0'],
    // A liveness bound, not a performance claim. This test exists to prove the oversell
    // invariant, and at 1000 simultaneous VUs against a single local process the latency
    // distribution is a property of the load generator and the host, not of correctness — a
    // tight bound here fails on a busy machine while zero-oversell is perfectly intact.
    // Order-creation latency is measured at a stated concurrency by throughput.js instead.
    'http_req_duration': ['p(99) < 15000'],
  },
};

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