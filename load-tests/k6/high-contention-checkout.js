/**
 * High-Contention Checkout Load Test
 *
 * Verifies zero oversell under extreme inventory contention:
 * - 200 virtual users each attempt to reserve 1 unit
 * - Inventory is pre-seeded to only 10 units (95% rejection rate)
 * - Expected: at most 10 succeed, at least 190 are rejected (409), zero 5xx errors
 *
 * Run:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=failure-test-item load-tests/k6/high-contention-checkout.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const successCount  = new Counter('checkout_success');
const rejectedCount = new Counter('checkout_rejected');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID  = __ENV.ITEM_ID  || 'failure-test-item';

// Treat 2xx and 4xx as expected (409 = correct inventory rejection)
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  vus: 200,
  iterations: 200,
  thresholds: {
    // No more than 10 orders should succeed against 10-unit stock
    'checkout_success':  ['count <= 10'],
    // The rest must be clean 409s, not errors
    'http_req_failed':   ['rate == 0'],
    'http_req_duration': ['p(95) < 3000'],
  },
};

export default function () {
  const payload = JSON.stringify({
    customerId: `fail-customer-${__VU}`,
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
    check(res, { 'no 5xx errors': () => false });
  }
}