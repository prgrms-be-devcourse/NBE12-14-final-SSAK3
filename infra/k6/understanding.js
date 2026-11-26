import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = 'http://localhost:8080';

const JOIN_CODE = __ENV.JOIN_CODE;
const POLL_ID = Number(__ENV.POLL_ID);
const RUN_ID = __ENV.RUN_ID || 'u01';
const VUS = Number(__ENV.VUS || 1);

const understandingResponseDuration = new Trend(
    'understanding_response_duration',
    true
);

const understandingFirstDuration = new Trend(
    'understanding_first_duration',
    true
);

const understandingChangeDuration = new Trend(
    'understanding_change_duration',
    true
);

const understandingRepeatDuration = new Trend(
    'understanding_repeat_duration',
    true
);

const understandingResponseSuccess = new Counter(
    'understanding_response_success'
);

const understandingSuccess = new Counter(
    'understanding_success'
);

export const options = {
    scenarios: {
        understanding_load: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '150s',
            gracefulStop: '5s',
        },
    },

    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        understanding_response_success: [
            `count==${VUS * 3}`,
        ],
        understanding_success: [
            `count==${VUS}`,
        ],
    },
};

export function setup() {
    if (!JOIN_CODE) {
        throw new Error('JOIN_CODE를 입력해야 합니다.');
    }

    if (!Number.isInteger(POLL_ID) || POLL_ID <= 0) {
        throw new Error('POLL_ID를 입력해야 합니다.');
    }

    if (!Number.isInteger(VUS) || VUS <= 0) {
        throw new Error('VUS는 양의 정수여야 합니다.');
    }

    if (!/^[a-zA-Z0-9]{1,10}$/.test(RUN_ID)) {
        throw new Error(
            'RUN_ID는 1~10자의 영문 또는 숫자여야 합니다.'
        );
    }

    const response = http.get(
        `${BASE_URL}/api/v1/public/rooms/${JOIN_CODE}`
    );

    if (response.status !== 200) {
        throw new Error('강연방 조회 실패');
    }

    const room = response.json('data');

    // 이해도 응답은 LIVE 상태에서만 가능하다.
    if (!room.canJoin || room.status !== 'LIVE') {
        throw new Error(
            'LIVE 상태의 테스트 방이 필요합니다.'
        );
    }

    console.log(`테스트 대상 강연방 ID: ${room.roomId}`);
    console.log(`이해도 체크 ID: ${POLL_ID}`);
    console.log(`동시 청중: ${VUS}명`);
    console.log(`예상 응답 요청: ${VUS * 3}건`);
    console.log(`예상 DB 저장: ${VUS}건`);

    return {
        roomId: room.roomId,

        // 청중 입장과 투표 조회를 마친 뒤
        // 동시에 첫 번째 응답을 보내도록 한다.
        startAt: Date.now() + 10000,
    };
}

// 이해도 응답을 전송하고 응답 내용을 검증한다.
function respond(
    url,
    csrf,
    participantId,
    optionId,
    expectedChanged,
    stage,
    trend
) {
    const response = http.put(
        url,
        JSON.stringify({
            optionId: optionId,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                [csrf.headerName]: csrf.token,
            },
            tags: {
                name: 'understanding_response',
                stage: stage,
            },
            timeout: '35s',
        }
    );

    understandingResponseDuration.add(
        response.timings.duration
    );

    trend.add(response.timings.duration);

    if (!check(response, {
        [`${stage} 응답 성공`]: (r) =>
            r.status === 200,
    })) {
        console.error(
            `VU ${__VU}: ${stage} 실패, `
            + `status=${response.status}, `
            + `body=${response.body}`
        );

        return null;
    }

    const result = response.json('data');

    const valid = check(result, {
        [`${stage} 투표 ID 일치`]: (r) =>
            r.pollId === POLL_ID,

        [`${stage} 참여자 ID 일치`]: (r) =>
            r.participantId === participantId,

        [`${stage} 선택지 일치`]: (r) =>
            r.optionId === optionId,

        [`${stage} 변경 여부 일치`]: (r) =>
            r.changed === expectedChanged,
    });

    if (!valid) {
        return null;
    }

    understandingResponseSuccess.add(1);

    return result;
}

export default function (data) {
    const nickname = `k6-${RUN_ID}-${__VU}`;

    // 1. CSRF 토큰 발급
    const csrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`
    );

    if (!check(csrfResponse, {
        'CSRF 발급 성공': (r) =>
            r.status === 200,
    })) {
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
        }
    );

    if (!check(joinResponse, {
        '신규 청중 입장 성공': (r) =>
            r.status === 201,
    })) {
        return;
    }

    const participant = joinResponse.json('data');

    if (!check(participant, {
        '올바른 강연방 입장': (p) =>
            p.roomId === data.roomId,

        '닉네임 일치': (p) =>
            p.nickname === nickname,

        '참여자 ID 발급': (p) =>
            p.participantId > 0,
    })) {
        return;
    }

    // 3. 청중 세션 인증
    const meResponse = http.get(
        `${BASE_URL}/api/v1/rooms/${data.roomId}/participants/me`
    );

    if (!check(meResponse, {
        '청중 세션 인증 성공': (r) =>
            r.status === 200,
    })) {
        return;
    }

    const me = meResponse.json('data');

    if (!check(me, {
        '참여자 ID 일치': (p) =>
            p.participantId === participant.participantId,

        '참여자 닉네임 일치': (p) =>
            p.nickname === nickname,
    })) {
        return;
    }

    // 4. 진행 중인 이해도 체크 조회
    const pollResponse = http.get(
        `${BASE_URL}/api/v1/rooms/${data.roomId}/polls?status=OPEN`
    );

    if (!check(pollResponse, {
        '진행 중인 투표 조회 성공': (r) =>
            r.status === 200,
    })) {
        return;
    }

    const pollData = pollResponse.json('data');

    const poll = pollData.polls.find(
        (p) => p.pollId === POLL_ID
    );

    if (!check(poll, {
        '이해도 체크 존재': (p) =>
            p !== undefined,

        '이해도 체크 타입 일치': (p) =>
            p?.type === 'UNDERSTANDING',

        '이해도 체크 진행 중': (p) =>
            p?.status === 'OPEN',

        '선택지 3개 존재': (p) =>
            p?.options?.length === 3,
    })) {
        return;
    }

    const remainingSeconds = (
        Date.parse(poll.closesAt)
        - Date.parse(pollData.serverTime)
    ) / 1000;

    if (!check(remainingSeconds, {
        '충분한 이해도 체크 시간 확보': (seconds) =>
            Number.isFinite(seconds) && seconds >= 60,
    })) {
        console.error(
            '이해도 체크 시간이 부족합니다. '
            + '새 이해도 체크를 시작해 주세요.'
        );

        return;
    }

    // 선택지 ID는 하드코딩하지 않고
    // 실제 서버에서 반환한 값을 사용한다.
    const statuses = [
        'UNDERSTOOD',
        'CONFUSED',
        'NOT_UNDERSTOOD',
    ];

    const optionIds = statuses.map(
        (status) => poll.options.find(
            (option) => option.understandingStatus === status
        )?.optionId
    );

    if (!check(optionIds, {
        '이해도 선택지 구성 정상': (ids) =>
            ids.every((id) =>
                Number.isInteger(id) && id > 0
            ) && new Set(ids).size === 3,
    })) {
        return;
    }

    // 청중마다 다른 선택지를 배정한다.
    const firstOptionId =
        optionIds[(__VU - 1) % 3];

    const finalOptionId =
        optionIds[__VU % 3];

    // 5. 응답 전송용 CSRF 토큰 확인
    const responseCsrf = http.get(
        `${BASE_URL}/api/v1/auth/csrf`
    );

    if (!check(responseCsrf, {
        '응답용 CSRF 발급 성공': (r) =>
            r.status === 200,
    })) {
        return;
    }

    const pollCsrf = responseCsrf.json('data');

    const responseUrl =
        `${BASE_URL}/api/v1/rooms/${data.roomId}`
        + `/polls/${POLL_ID}/responses/me`;

    // 모든 청중의 첫 응답 시점을 맞춘다.
    const waitSeconds =
        (data.startAt - Date.now()) / 1000;

    if (!check(waitSeconds, {
        '동시 응답 준비 시간 충족': (seconds) =>
            seconds > 0,
    })) {
        return;
    }

    sleep(waitSeconds);

    // 6. 첫 번째 응답: 신규 저장
    const first = respond(
        responseUrl,
        pollCsrf,
        participant.participantId,
        firstOptionId,
        true,
        'first',
        understandingFirstDuration
    );

    if (!first) {
        return;
    }

    sleep(1);

    // 7. 두 번째 응답: 기존 선택 변경
    const changed = respond(
        responseUrl,
        pollCsrf,
        participant.participantId,
        finalOptionId,
        true,
        'change',
        understandingChangeDuration
    );

    if (!changed) {
        return;
    }

    sleep(1);

    // 8. 세 번째 응답: 동일한 선택 반복
    const repeated = respond(
        responseUrl,
        pollCsrf,
        participant.participantId,
        finalOptionId,
        false,
        'repeat',
        understandingRepeatDuration
    );

    if (!repeated) {
        return;
    }

    const repeatedValid = check(repeated, {
        '동일 선택 반복 시 응답 시각 유지': (r) =>
            r.respondedAt === changed.respondedAt,
    });

    if (!repeatedValid) {
        return;
    }

    // 9. 최종 저장된 응답 조회
    const finalResponse = http.get(responseUrl);

    if (!check(finalResponse, {
        '최종 이해도 응답 조회 성공': (r) =>
            r.status === 200,
    })) {
        return;
    }

    const finalResult = finalResponse.json('data');

    const success = check(finalResult, {
        '최종 투표 ID 일치': (r) =>
            r.pollId === POLL_ID,

        '최종 참여자 ID 일치': (r) =>
            r.participantId === participant.participantId,

        '최종 선택지 일치': (r) =>
            r.optionId === finalOptionId,

        '최종 응답 시각 일치': (r) =>
            r.respondedAt === changed.respondedAt,
    });

    if (success) {
        understandingSuccess.add(1);
    }
}