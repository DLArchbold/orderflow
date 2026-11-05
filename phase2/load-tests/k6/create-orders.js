// Arrival-rate load test for POST /api/orders (PLAN step 25).
//
//   k6 run phase2/load-tests/k6/create-orders.js                              # 50 orders/s for 3 minutes, local stack
//   k6 run -e RATE=50 -e DURATION=30s phase2/load-tests/k6/create-orders.js   # smoke run
//   k6 run -e BASE_URL=http://<alb> -e KEYCLOAK_URL=http://<alb> ...          # against AWS
//
// A VU-based test finds how many requests the system handles when users wait for each answer. An arrival-rate test
// holds the orders per second constant, which is what "sustained N orders/s at p95 N ms" actually claims.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const USERS = (__ENV.USERS || 'alice,bob').split(',');

const created = new Counter('orders_created');   // counted from the 201 responses, for the resilience drill (step 26)

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),            // iterations (orders) per second
      timeUnit: '1s',
      duration: __ENV.DURATION || '3m',
      preAllocatedVUs: 100,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

// One token per user, fetched once. The realm gives access tokens 30 minutes, which covers the run.
export function setup() {
  return USERS.map((user) => {
    const res = http.post(
      `${KEYCLOAK_URL}/realms/orderflow/protocol/openid-connect/token`,
      { client_id: 'orderflow-cli', grant_type: 'password', username: user, password: `${user}-dev-pw` },
    );
    if (res.status !== 200) fail(`could not get a token for ${user}: ${res.status} ${res.body}`);
    return res.json('access_token');
  });
}

export default function (tokens) {
  const token = tokens[__VU % tokens.length];
  const sku = Math.random() < 0.5 ? 'SKU-1' : 'SKU-2';   // mix both SKUs
  const res = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ items: [{ sku, quantity: 1 }] }),   // the owner comes from the token, not the body
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } },
  );
  if (check(res, { created: (r) => r.status === 201 })) created.add(1);
}
