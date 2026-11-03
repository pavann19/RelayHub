import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

export const options = {
  scenarios: {
    rate_limit_blast: {
      executor: 'constant-arrival-rate',
      rate: 300,              // 300 req/s to blast past burst limit
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 50,
      maxVUs: 100,
    },
  },
  thresholds: {
    'rate_limited_429s': ['count>50'], // Must trigger 429 rate limit
  },
};

const BASE_URL = __ENV.RELAYHUB_URL || 'http://localhost:8080';
// Uses the starter free key or default key
const API_KEY = __ENV.RELAYHUB_API_KEY || 'rlh_live_demo1234567890abcdef';

const rateLimitedCounter = new Counter('rate_limited_429s');

export default function () {
  const payload = JSON.stringify({
    eventType: 'telemetry.ping',
    payload: JSON.stringify({ ping: true, ts: Date.now() })
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/events`, payload, params);

  if (res.status === 429) {
    rateLimitedCounter.add(1);
    check(res, {
      'has Retry-After header': (r) => r.headers['Retry-After'] !== undefined,
      'has X-RateLimit-Remaining: 0': (r) => r.headers['X-Ratelimit-Remaining'] === '0',
      'has RFC 7807 problem detail': (r) => JSON.parse(r.body).title === 'Too Many Requests',
    });
  } else {
    check(res, {
      'status is 201': (r) => r.status === 201,
    });
  }
}

