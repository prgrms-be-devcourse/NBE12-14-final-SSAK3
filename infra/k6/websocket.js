import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = 'http://localhost:8080';
const WS_URL = 'ws://localhost:8080/ws';
const ORIGIN = 'http://localhost:5173';

const JOIN_CODE = __ENV.JOIN_CODE;
const RUN_ID = __ENV.RUN_ID || 'ws01';
const VUS = Number(__ENV.VUS || 1);
const HOLD_SECONDS = Number(__ENV.HOLD_SECONDS || 60);

const stompConnectDuration = new Trend(
    'stomp_connect_duration',
    true
);

const stompConnectSuccess = new Counter(
    'stomp_connect_success'
);

const websocketHoldSuccess = new Counter(
    'websocket_hold_success'
);

export const options = {
    scenarios: {
        websocket_connection: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: `${HOLD_SECONDS + 60}s`,
            gracefulStop: '5s',
        },
    },

    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        stomp_connect_success: [`count==${VUS}`],
        websocket_hold_success: [`count==${VUS}`],
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
        throw new Error('입장 가능한 테스트 방이 필요합니다.');
    }

    console.log(`테스트 대상 강연방 ID: ${room.roomId}`);
    console.log(`동시 연결 청중: ${VUS}명`);
    console.log(`연결 유지 시간: ${HOLD_SECONDS}초`);

    return {
        roomId: room.roomId,
    };
}

// STOMP 명령어와 헤더를 전송 가능한 프레임으로 변환한다.
function stompFrame(command, headers = {}, body = '') {
    const headerLines = Object.entries(headers)
        .map(([key, value]) => `${key}:${value}`)
        .join('\n');

    return `${command}\n${headerLines}\n\n${body}\x00`;
}

export default function (data) {
    const nickname = `k6-${RUN_ID}-${__VU}`;

    // 1. CSRF 토큰 발급
    const csrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`
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
        }
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
        `${BASE_URL}/api/v1/rooms/${data.roomId}/participants/me`
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

    if (!meValid) {
        return;
    }

    // 4. WebSocket 연결 전 현재 세션의 CSRF 토큰을 확인한다.
    const stompCsrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`
    );

    const stompCsrfSuccess = check(stompCsrfResponse, {
        'STOMP CSRF 발급 성공': (r) => r.status === 200,
    });

    if (!stompCsrfSuccess) {
        return;
    }

    const stompCsrf = stompCsrfResponse.json('data');

    let connected = false;
    let subscribeSent = false;
    let holdCompleted = false;
    let connectionError = false;
    let connectStartedAt = 0;

    // 5. 기존 HTTP 세션 쿠키를 사용해 WebSocket에 연결한다.
    const response = ws.connect(
        WS_URL,
        {
            jar: http.cookieJar(),
            headers: {
                Origin: ORIGIN,
                'Sec-WebSocket-Protocol': 'v12.stomp',
            },
        },
        function (socket) {
            socket.on('open', function () {
                connectStartedAt = Date.now();

                // WebSocket 연결 이후 STOMP 인증을 요청한다.
                socket.send(
                    stompFrame('CONNECT', {
                        'accept-version': '1.2',
                        host: 'localhost',
                        'heart-beat': '0,0',
                        'X-Chat-Role': 'PARTICIPANT',
                        [stompCsrf.headerName]: stompCsrf.token,
                    })
                );
            });

            socket.on('message', function (message) {
                const frames = message.split('\x00');

                for (const rawFrame of frames) {
                    const frame = rawFrame.trimStart();

                    if (!frame) {
                        continue;
                    }

                    const command = frame
                        .split('\n')[0]
                        .trim();

                    // STOMP 인증이 완료된 경우
                    if (command === 'CONNECTED' && !connected) {
                        connected = true;

                        stompConnectDuration.add(
                            Date.now() - connectStartedAt
                        );

                        stompConnectSuccess.add(1);

                        // 채팅 채널 구독
                        socket.send(
                            stompFrame('SUBSCRIBE', {
                                id: 'chat',
                                destination:
                                    `/topic/rooms/${data.roomId}/chat`,
                                ack: 'auto',
                                'X-Chat-Role': 'PARTICIPANT',
                            })
                        );

                        // 개인 오류 채널 구독
                        socket.send(
                            stompFrame('SUBSCRIBE', {
                                id: 'errors',
                                destination: '/user/queue/errors',
                                ack: 'auto',
                            })
                        );

                        subscribeSent = true;

                        // 지정된 시간 동안 연결을 유지한다.
                        socket.setTimeout(function () {
                            holdCompleted = true;

                            socket.send(
                                stompFrame('DISCONNECT')
                            );

                            socket.close();
                        }, HOLD_SECONDS * 1000);
                    }

                    if (command === 'ERROR') {
                        connectionError = true;

                        console.error(
                            `VU ${__VU}: STOMP 오류 발생`
                        );

                        socket.close();
                    }
                }
            });

            socket.on('error', function () {
                connectionError = true;

                console.error(
                    `VU ${__VU}: WebSocket 오류 발생`
                );
            });

            // STOMP 인증 응답이 없으면 연결을 종료한다.
            socket.setTimeout(function () {
                if (!connected) {
                    connectionError = true;

                    console.error(
                        `VU ${__VU}: STOMP 연결 시간 초과`
                    );

                    socket.close();
                }
            }, 10000);
        }
    );

    const websocketValid = check(response, {
        'WebSocket 핸드셰이크 성공': (r) =>
            r && r.status === 101,

        'STOMP 연결 성공': () =>
            connected,

        '채팅 및 오류 채널 구독 요청 완료': () =>
            subscribeSent,

        '지정 시간 동안 연결 유지 성공': () =>
            holdCompleted,

        'WebSocket 및 STOMP 오류 없음': () =>
            !connectionError,
    });

    if (websocketValid) {
        websocketHoldSuccess.add(1);
    }
}