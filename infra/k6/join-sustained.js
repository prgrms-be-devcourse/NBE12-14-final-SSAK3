import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = 'http://localhost:8080';
const JOIN_CODE = __ENV.JOIN_CODE;
const RUN_ID = __ENV.RUN_ID || 's05';
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '60s';

const participantJoinDuration = new Trend(
    'participant_join_duration',
    true
);

const participantJoinSuccess = new Counter(
    'participant_join_success'
);

export const options = {
    scenarios: {
        participant_join_sustained: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 20,
            maxVUs: 100,
            gracefulStop: '30s',
        },
    },

    // 반복 실행마다 새로운 청중 세션을 사용한다.
    noCookiesReset: false,

    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        dropped_iterations: ['count==0'],
    },
};

export function setup() {
    if (!JOIN_CODE) {
        throw new Error('JOIN_CODE를 입력해야 합니다.');
    }

    const response = http.get(
        `${BASE_URL}/api/v1/public/rooms/${JOIN_CODE}`
    );

    if (response.status !== 200) {
        throw new Error('강연방 조회 실패');
    }

    const room = response.json('data');

    // 입장 가능한 READY, LIVE 상태에서만 테스트한다.
    if (!room.canJoin || !['READY', 'LIVE'].includes(room.status)) {
        throw new Error(
            '입장 가능한 테스트 방이 필요합니다.'
        );
    }

    console.log(`테스트 대상 강연방 ID: ${room.roomId}`);
    console.log(`초당 입장 시도: ${RATE}명`);
    console.log(`테스트 시간: ${DURATION}`);

    return {
        roomId: room.roomId,
    };
}

export default function (data) {
    // VU가 재사용되더라도 매번 다른 청중을 생성한다.
    const nickname =
        `k6-${RUN_ID}-${exec.scenario.iterationInTest}`;

    // 1. CSRF 토큰 발급
    const csrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`,
        {
            tags: { name: 'csrf' },
        }
    );

    const csrfSuccess = check(csrfResponse, {
        'CSRF 발급 성공': (r) => r.status === 200,
    });

    if (!csrfSuccess) {
        return;
    }

    const csrf = csrfResponse.json('data');

    // 2. 신규 청중 입장
    const joinResponse = http.post(
        `${BASE_URL}/api/v1/public/rooms/${JOIN_CODE}/participants`,
        JSON.stringify({
            nickname: nickname,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                [csrf.headerName]: csrf.token,
                'Idempotency-Key': crypto.randomUUID(),
            },
            tags: { name: 'participant_join' },
        }
    );

    participantJoinDuration.add(
        joinResponse.timings.duration
    );

    const joinSuccess = check(joinResponse, {
        '신규 청중 입장 성공': (r) => r.status === 201,
    });

    if (!joinSuccess) {
        return;
    }

    const participant = joinResponse.json('data');

    const participantValid = check(participant, {
        '올바른 강연방 입장': (p) =>
            p.roomId === data.roomId,

        '닉네임 일치': (p) =>
            p.nickname === nickname,

        '참여자 ID 발급': (p) =>
            p.participantId > 0,
    });

    if (!participantValid) {
        return;
    }

    // 3. 청중 세션 인증
    const meResponse = http.get(
        `${BASE_URL}/api/v1/rooms/${data.roomId}/participants/me`,
        {
            tags: { name: 'participant_me' },
        }
    );

    const meSuccess = check(meResponse, {
        '청중 세션 인증 성공': (r) => r.status === 200,
    });

    if (!meSuccess) {
        return;
    }

    const me = meResponse.json('data');

    const meValid = check(me, {
        '참여자 ID 일치': (p) =>
            p.participantId === participant.participantId,

        '참여자 닉네임 일치': (p) =>
            p.nickname === nickname,
    });

    if (meValid) {
        participantJoinSuccess.add(1);
    }
}