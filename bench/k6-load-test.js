import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const options = {
  stages: [
    { duration: '10s', target: 20 },  // Warm up
    { duration: '30s', target: 50 },  // Sustained load
    { duration: '20s', target: 100 }, // Peak traffic spike
    { duration: '10s', target: 0 },   // Cool down
  ],
  thresholds: {
    http_req_duration: ['p(95)<150', 'p(99)<300'], // 95% of requests under 150ms
    http_req_failed: ['rate<0.01'],                 // Error rate < 1%
    'successful_ingestions': ['rate>0.99'],
  },
};

const BASE_URL = __ENV.RELAYHUB_URL || 'http://localhost:8080';
const API_KEY = __ENV.RELAYHUB_API_KEY || 'rlh_live_demo1234567890abcdef';

const successfulIngestions = new Rate('successful_ingestions');
const ingestionLatency = new Trend('ingestion_latency');

export default function () {
  const eventId = `evt_load_${__VU}_${__ITER}_${Date.now()}`;
  const payload = JSON.stringify({
    eventType: 'payment.completed',
    payload: JSON.stringify({
      orderId: `ord_${Math.floor(Math.random() * 1000000)}`,
      amount: (Math.random() * 500).toFixed(2),
      currency: 'EUR',
      customer: `cust_${__VU}`,
      timestamp: Date.now()
    })
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
      'Idempotency-Key': `idem_${eventId}`
    },
    timeout: '5s'
  };

  const startTime = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/events`, payload, params);
  ingestionLatency.add(Date.now() - startTime);

  const isSuccess = check(res, {
    'status is 201 Created': (r) => r.status === 201,
    'has eventId': (r) => {
      try { return JSON.parse(r.body).eventId !== undefined; } catch(e) { return false; }
    },
    'has X-RateLimit headers': (r) => (r.headers['X-Ratelimit-Limit'] || r.headers['X-RateLimit-Limit']) !== undefined,
  });

  successfulIngestions.add(isSuccess);

  sleep(0.05); // 20 requests/sec per VU
}

