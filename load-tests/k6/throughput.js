/**
 * Throughput & Latency Load Test
 *
 * Measures p95/p99 latency and RPS for order creation under sustained load:
 * - Ramp up to 200 virtual users over 30s
 * - Sustain 200 VUs for 60s (~10K total requests)
 * - Ramp down over 10s
 * - Inventory pre-seeded to 50K (never runs out during test)
 *
 * Run:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=throughput-item load-tests/k6/throughput.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const successCount = new Counter('orders_created');
const errorRate    = new Rate('order_errors');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID  = __ENV.ITEM_ID  || 'throughput-item';

export const options = {
  stages: [
    { duration: '30s', target: 200 },
    { duration: '60s', target: 200 },
    { duration: '10s', target: 0   },
  ],
  thresholds: {
    'http_req_duration': ['p(95) < 2000', 'p(99) < 4000'],
    'http_req_failed':   ['rate < 0.01'],
    'order_errors':      ['rate < 0.01'],
  },
};

let reqCounter = 0;

export default function () {
  reqCounter++;
  const key = `throughput-${__VU}-${__ITER}-${Date.now()}`;

  const payload = JSON.stringify({
    customerId: `customer-${__VU}`,
    items: [{ itemId: ITEM_ID, quantity: 1 }],
  });

  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    },
  });

  const ok = res.status === 201;
  successCount.add(ok ? 1 : 0);
  errorRate.add(res.status >= 500 ? 1 : 0);

  check(res, { 'order created (201)': (r) => r.status === 201 });
}