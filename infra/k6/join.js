import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = 'http://localhost:8080';
const JOIN_CODE = __ENV.JOIN_CODE;
const RUN_ID = __ENV.RUN_ID;
const VUS = Number(__ENV.VUS || 1);

const joinDuration = new Trend('participant_join_duration', true);

export const options = {
    scenarios: {
        participant_join: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '60s',
        },
    },
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
    },
};

export function setup() {
    if (!JOIN_CODE || !RUN_ID) {
        throw new Error('JOIN_CODE와 RUN_ID를 설정해야 합니다.');
    }

    const response = http.get(
        `${BASE_URL}/api/v1/public/rooms/${JOIN_CODE}`
    );

    if (response.status !== 200) {
        throw new Error('강연방 조회 실패');
    }

    const room = response.json('data');

    if (!room.canJoin || !['READY', 'LIVE'].includes(room.status)) {
        throw new Error('입장 가능한 테스트 방이 필요합니다.');
    }

    console.log(`테스트 대상 강연방 ID: ${room.roomId}`);

    return {
        roomId: room.roomId,
    };
}

export default function (data) {
    const nickname = `k6-${RUN_ID}-${__VU}`;

    // CSRF 토큰과 HTTP 세션 발급
    const csrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`,
        { tags: { name: 'csrf' } }
    );

    if (!check(csrfResponse, {
        'CSRF 발급 성공': (r) => r.status === 200,
    })) {
        return;
    }

    const csrf = csrfResponse.json('data');

    // 청중별 고유 입장 요청
    const joinResponse = http.post(
        `${BASE_URL}/api/v1/public/rooms/${JOIN_CODE}/participants`,
        JSON.stringify({ nickname }),
        {
            headers: {
                'Content-Type': 'application/json',
                [csrf.headerName]: csrf.token,
                'Idempotency-Key': crypto.randomUUID(),
            },
            tags: {
                name: 'participant_join',
            },
        }
    );

    joinDuration.add(joinResponse.timings.duration);

    if (!check(joinResponse, {
        '신규 청중 입장 성공': (r) => r.status === 201,
    })) {
        return;
    }

    const participant = joinResponse.json('data');

    check(participant, {
        '올바른 강연방 입장': (p) => p.roomId === data.roomId,
        '닉네임 일치': (p) => p.nickname === nickname,
        '참여자 ID 발급': (p) => p.participantId > 0,
    });

    // 발급된 쿠키로 실제 참여자 인증 검증
    const meResponse = http.get(
        `${BASE_URL}/api/v1/rooms/${data.roomId}/participants/me`,
        { tags: { name: 'participant_me' } }
    );

    if (!check(meResponse, {
        '청중 세션 인증 성공': (r) => r.status === 200,
    })) {
        return;
    }

    const me = meResponse.json('data');

    check(me, {
        '참여자 ID 일치': (p) =>
            p.participantId === participant.participantId,
        '참여자 닉네임 일치': (p) =>
            p.nickname === nickname,
    });
}