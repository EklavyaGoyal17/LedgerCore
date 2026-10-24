import http from 'k6/http';
import { check, sleep } from 'k6';

// Supply an existing owner token and funded source/destination accounts. No credentials are logged.
const base = __ENV.BASE_URL || 'http://localhost:8080';
const mode = __ENV.MODE || 'mixed';
export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '30s',
  thresholds: { checks: ['rate>0.99'] },
};
export default function () {
  const headers = { Authorization: `Bearer ${__ENV.TOKEN}`, 'Content-Type': 'application/json' };
  if (!__ENV.TOKEN || !__ENV.SOURCE || !__ENV.DESTINATION) throw new Error('Set TOKEN, SOURCE, DESTINATION');
  if (mode === 'accounts') {
    check(http.get(`${base}/api/v1/accounts`, { headers }), { 'account read': r => r.status === 200 });
  } else if (mode === 'balance' || (mode === 'mixed' && __ITER % 3 !== 0)) {
    check(http.get(`${base}/api/v1/accounts/${__ENV.SOURCE}/balance`, { headers }), { 'balance read': r => r.status === 200 });
  } else {
    headers['Idempotency-Key'] = `k6-${__ENV.RUN_ID || 'set-a-unique-run-id'}-${__VU}-${__ITER}`;
    const body = JSON.stringify({ sourceAccountId: __ENV.SOURCE, destinationAccountId: __ENV.DESTINATION, amount: '0.01', currency: __ENV.CURRENCY || 'INR' });
    check(http.post(`${base}/api/v1/transfers`, body, { headers }), { 'transfer accepted or insufficient': r => [201, 422].includes(r.status) });
  }
  sleep(0.1);
}
