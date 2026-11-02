import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// Supply an existing owner token and funded source/destination accounts. No credentials are logged.
const base = __ENV.BASE_URL || 'http://localhost:8080';
const mode = __ENV.MODE || 'mixed';
const fixtures = __ENV.FIXTURES_FILE ? new SharedArray('accounts', () => JSON.parse(open(__ENV.FIXTURES_FILE))) : null;
const completed = new Counter('ledgercore_completed_postings');
const rejected = new Counter('ledgercore_insufficient_funds');
http.setResponseCallback(http.expectedStatuses(200, 201, 422));
export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '30s',
  thresholds: { checks: ['rate>0.999'], http_req_failed: ['rate<0.001'] },
};
export default function () {
  const fixture = fixtures ? fixtures[(mode === 'contention' ? 0 : __VU - 1) % fixtures.length] :
    { token: __ENV.TOKEN, source: __ENV.SOURCE, destination: __ENV.DESTINATION };
  const headers = { Authorization: `Bearer ${fixture.token}`, 'Content-Type': 'application/json' };
  if (!fixture.token || !fixture.source || !fixture.destination) throw new Error('Set fixtures or TOKEN, SOURCE, DESTINATION');
  if (mode === 'accounts') {
    check(http.get(`${base}/api/v1/accounts`, { headers }), { 'account read': r => r.status === 200 });
  } else if (mode === 'balance' || (mode === 'mixed' && __ITER % 3 !== 0)) {
    check(http.get(`${base}/api/v1/accounts/${fixture.source}/balance`, { headers }), { 'balance read': r => r.status === 200 });
  } else {
    if (!__ENV.RUN_ID) throw new Error('Set a unique RUN_ID to distinguish posting from replay');
    headers['Idempotency-Key'] = `k6-${__ENV.RUN_ID}-${__VU}-${__ITER}`;
    const body = JSON.stringify({ sourceAccountId: fixture.source, destinationAccountId: fixture.destination, amount: '0.01', currency: __ENV.CURRENCY || 'INR' });
    const result = http.post(`${base}/api/v1/transfers`, body, { headers });
    if (result.status === 201) completed.add(1);
    if (result.status === 422) rejected.add(1);
    check(result, { 'transfer accepted or insufficient': r => [201, 422].includes(r.status) });
  }
  sleep(Number(__ENV.THINK_TIME || '0.1'));
}
