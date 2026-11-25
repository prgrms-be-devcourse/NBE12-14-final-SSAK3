import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    vus: 1,
    duration: '10s',

    thresholds: {
        http_req_failed: ['rate==0'],
        checks: ['rate==1'],
    },
};

export default function () {
    const response = http.get(`${BASE_URL}/actuator/health`);

    check(response, {
        'HTTP 상태 코드 200': (res) => res.status === 200,
        '서버 상태 UP': (res) => res.json('status') === 'UP',
    });

    sleep(1);
}