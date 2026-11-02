// k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=<jwt> load-tests/k6/create-orders.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const res = http.post(
    `${__ENV.BASE_URL}/api/orders`,
    JSON.stringify({ items: [{ sku: 'SKU-1', quantity: 1 }] }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${__ENV.TOKEN}`,
        'Idempotency-Key': uuidv4(),
      },
    },
  );
  check(res, { 'created': (r) => r.status === 201 });
}
