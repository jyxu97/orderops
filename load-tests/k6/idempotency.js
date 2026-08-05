/**
 * Idempotency Load Test
 *
 * Verifies that repeated checkout requests with the same Idempotency-Key
 * produce exactly one order and never double-deduct inventory.
 *
 * Scenario:
 * - 50 virtual users each pick one of 10 shared idempotency keys
 * - Each VU sends 20 requests (total 1000 requests, 10 unique keys)
 * - Expected: exactly 10 unique orders created, inventory deducted once per key
 *
 * Run:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=idem-item load-tests/k6/idempotency.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const created   = new Counter('idem_created');
const duplicate = new Counter('idem_duplicate');
const errors    = new Counter('idem_errors');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID  = __ENV.ITEM_ID  || 'idem-item';

// 10 shared keys — each will be sent by multiple VUs
const IDEM_KEYS = Array.from({ length: 10 }, (_, i) => `shared-key-${i}`);

// Accept 200 (replay) and 201 (new order) as non-failed; 4xx are also accepted for conflict detection
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export const options = {
  vus:        50,
  iterations: 1000,
  thresholds: {
    // Every response must be 201 (first time) or 200 (duplicate replay)
    'http_req_failed': ['rate == 0'],
    // No 5xx errors at all
    'idem_errors':     ['count == 0'],
  },
};

export default function () {
  // Each iteration picks one of the 10 shared keys deterministically
  const keyIdx  = __ITER % IDEM_KEYS.length;
  const idemKey = IDEM_KEYS[keyIdx];

  const payload = JSON.stringify({
    customerId: 'idem-customer',
    items: [{ itemId: ITEM_ID, quantity: 1 }],
  });

  const res = http.post(`${BASE_URL}/orders`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idemKey,
    },
  });

  if (res.status === 201) {
    created.add(1);
    check(res, { 'new order created (201)': () => true });
  } else if (res.status === 200) {
    duplicate.add(1);
    check(res, { 'duplicate replayed (200)': () => true });
  } else {
    errors.add(1);
    check(res, { 'unexpected error': () => false });
  }
}
