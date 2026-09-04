/**
 * Throughput & Latency Load Test
 *
 * Measures p95/p99 latency and RPS for order creation under sustained load.
 *
 * Concurrency is a parameter, not a constant: a latency figure is only meaningful alongside the
 * load level it was measured at, so VUS is explicit and reported with every result.
 * - Ramp to VUS over 30s, sustain 60s, ramp down 10s
 * - Inventory pre-seeded to 50K (never runs out during the test)
 *
 * Run:
 *   k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=throughput-item \
 *          -e VUS=200 load-tests/k6/throughput.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const successCount = new Counter('orders_created');
const errorRate    = new Rate('order_errors');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID  = __ENV.ITEM_ID  || 'throughput-item';
const VUS      = Number(__ENV.VUS || 200);

export const options = {
  stages: [
    { duration: '30s', target: VUS },
    { duration: '60s', target: VUS },
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