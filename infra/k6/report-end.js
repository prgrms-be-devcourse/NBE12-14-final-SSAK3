import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = (__ENV.BASE_URL || 'http://127.0.0.1:18640').replace(/\/$/, '');
const mock = (__ENV.MOCK_URL || 'http://127.0.0.1:18641').replace(/\/$/, '');
const api = `${base}/api/v1`;
const pollMs = positiveInteger('POLL_MS', 5000);
const timeoutSeconds = positiveInteger('TIMEOUT_SECONDS', 180);
if (!__ENV.FIXTURE_FILE) throw new Error('FIXTURE_FILE must point to the generated room fixture.');
const { rooms } = JSON.parse(open(__ENV.FIXTURE_FILE));
if (!Array.isArray(rooms) || !rooms.length ||
    !rooms.every(r => Number.isSafeInteger(r.roomId) && r.roomId > 0 && r.email && r.password) ||
    new Set(rooms.map(r => r.roomId)).size !== rooms.length) {
    throw new Error('Fixture requires distinct roomId values and an email/password for each room.');
}

const startOffset = new Trend('room_end_start_offset_ms', true);
const endLatency = new Trend('room_end_ms', true);
const receiptLatency = new Trend('report_receipt_observed_ms', true);
const completionLatency = new Trend('report_completed_observed_ms', true);
const completed = new Rate('report_completed_rate');
const attempts = new Counter('report_rooms_measured');
const statusCodes = http.expectedStatuses(200, 404);

export const options = {
    scenarios: {
        end_rooms: {
            executor: 'per-vu-iterations', vus: rooms.length, iterations: 1,
            maxDuration: `${timeoutSeconds + 30}s`,
        },
    },
    setupTimeout: '5m',
    summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'max'],
    thresholds: {
        http_req_failed: ['rate==0'], checks: ['rate==1'],
        report_completed_rate: ['rate==1'], report_rooms_measured: [`count==${rooms.length}`],
        ...(__ENV.END_P95_MS ? { room_end_ms: [`p(95)<=${positiveInteger('END_P95_MS')}`] } : {}),
        ...(__ENV.COMPLETE_P95_MS ? { report_completed_observed_ms: [`p(95)<=${positiveInteger('COMPLETE_P95_MS')}`] } : {}),
    },
};

function positiveInteger(name, fallback) {
    const value = Number(__ENV[name] ?? fallback);
    if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer.`);
    return value;
}

function csrf(jar) {
    const res = http.get(`${api}/auth/csrf`, { jar, tags: { name: 'setup_csrf' } });
    if (res.status !== 200) throw new Error(`CSRF bootstrap failed: HTTP ${res.status}`);
    const { headerName, token } = res.json('data');
    if (!headerName || !token) throw new Error('Missing CSRF header or token.');
    return { [headerName]: token };
}

// Authenticate before all VUs start, so password verification does not stagger room ends.
export function setup() {
    return rooms.map(({ roomId, email, password }) => {
        const jar = new http.CookieJar();
        const login = http.post(`${api}/auth/login`, JSON.stringify({ email, password }), {
            jar, headers: { 'Content-Type': 'application/json', ...csrf(jar) }, tags: { name: 'setup_login' },
        });
        if (login.status !== 200 || login.json('data.email') !== email) {
            throw new Error(`Host login failed for room ${roomId}: HTTP ${login.status}`);
        }
        const roomUrl = `${api}/rooms/${roomId}`;
        const room = http.get(roomUrl, { jar, tags: { name: 'setup_live_room' } });
        if (room.status !== 200 || room.json('data.status') !== 'LIVE') {
            throw new Error(`Room ${roomId} must be LIVE and owned by its fixture host.`);
        }
        const headers = csrf(jar);
        return { roomId, headers, cookies: jar.cookiesForURL(roomUrl) };
    });
}

export default function (hosts) {
    const { roomId, headers, cookies } = hosts[exec.scenario.iterationInTest];
    const path = `${api}/rooms/${roomId}`;
    const jar = http.cookieJar();
    for (const [name, values] of Object.entries(cookies)) {
        for (const value of values) jar.set(base, name, value, { path: '/' });
    }
    const start = Date.now();
    startOffset.add(start - exec.scenario.startTime);
    const deadline = start + timeoutSeconds * 1000;
    let success = false;
    try {
        const end = http.post(`${path}/end`, null, {
            headers, timeout: `${Math.min(timeoutSeconds, 10)}s`, tags: { name: 'room_end' },
        });
        endLatency.add(Date.now() - start);
        if (end.status !== 200 || end.json('data.status') !== 'ENDED' || end.json('data.roomId') !== roomId) {
            throw new Error(`end HTTP ${end.status}`);
        }
        let received = false;
        while (Date.now() < deadline) {
            const status = http.get(`${path}/reports/status`, {
                timeout: `${Math.max(1, Math.min(10000, deadline - Date.now()))}ms`,
                responseCallback: statusCodes, tags: { name: 'report_status' },
            });
            if (status.status === 200) {
                const data = status.json('data');
                if (!received) {
                    receiptLatency.add(Date.now() - start);
                    received = true;
                }
                if (data.status === 'COMPLETED' && Date.now() <= deadline) {
                    completionLatency.add(Date.now() - start);
                    success = true;
                    return;
                }
                if (data.status === 'FAILED' && data.failureCategory !== 'UNCONFIRMED') {
                    throw new Error(`report FAILED (${data.failureCategory})`);
                }
            } else if (received || status.status !== 404 || status.json('code') !== 'REPORT_NOT_FOUND') {
                throw new Error(`status HTTP ${status.status}`);
            }
            sleep(Math.max(0, Math.min(pollMs, deadline - Date.now())) / 1000);
        }
        throw new Error('completion deadline elapsed');
    } catch (error) {
        console.error(`Room ${roomId}: ${error.message}`);
    } finally {
        // Timings describe observed successes. Both counters must pass for a valid run.
        completed.add(success);
        attempts.add(1);
    }
}

export function teardown() {
    const res = http.get(`${mock}/stats`, { tags: { name: 'mock_stats' } });
    const byRoom = res.status === 200 ? res.json('byRoom') : {};
    for (const { roomId } of rooms) {
        check(byRoom[roomId], { 'one provider creation per fixture room': n => n === 1 });
    }
}
