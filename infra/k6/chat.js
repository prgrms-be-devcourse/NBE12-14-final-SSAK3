import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = 'http://localhost:8080';
const WS_URL = 'ws://localhost:8080/ws';
const ORIGIN = 'http://localhost:5173';

const JOIN_CODE = __ENV.JOIN_CODE;
const RUN_ID = __ENV.RUN_ID || 'c01';
const VUS = Number(__ENV.VUS || 1);
const MESSAGES = Number(__ENV.MESSAGES || 12);
const INTERVAL_SECONDS = Number(__ENV.INTERVAL_SECONDS || 5);

const WARMUP_SECONDS = 15;
const DRAIN_SECONDS = 10;

const EXPECTED_SENT = VUS * MESSAGES;
const EXPECTED_RECEIVED = VUS * MESSAGES * VUS;

const chatSent = new Counter('chat_sent');
const chatReceived = new Counter('chat_received');
const chatOwnReceived = new Counter('chat_own_received');
const chatSuccess = new Counter('chat_success');

const chatBroadcastDuration = new Trend(
    'chat_broadcast_duration',
    true
);

export const options = {
    scenarios: {
        chat_load: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration:
                `${WARMUP_SECONDS + MESSAGES * INTERVAL_SECONDS + DRAIN_SECONDS + 30}s`,
            gracefulStop: '5s',
        },
    },

    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        chat_sent: [`count==${EXPECTED_SENT}`],
        chat_received: [`count==${EXPECTED_RECEIVED}`],
        chat_own_received: [`count==${EXPECTED_SENT}`],
        chat_success: [`count==${VUS}`],
    },
};

export function setup() {
    if (!JOIN_CODE) {
        throw new Error('JOIN_CODE를 입력해야 합니다.');
    }

    if (
        ![VUS, MESSAGES, INTERVAL_SECONDS].every(
            (value) => Number.isInteger(value) && value > 0
        )
    ) {
        throw new Error('테스트 설정은 양의 정수여야 합니다.');
    }

    if (!/^[a-zA-Z0-9]{1,10}$/.test(RUN_ID)) {
        throw new Error('RUN_ID는 1~10자의 영문 또는 숫자여야 합니다.');
    }

    const response = http.get(
        `${BASE_URL}/api/v1/public/rooms/${JOIN_CODE}`
    );

    if (response.status !== 200) {
        throw new Error('강연방 조회 실패');
    }

    const room = response.json('data');

    // 채팅 전송은 LIVE 상태에서만 가능하다.
    if (!room.canJoin || room.status !== 'LIVE') {
        throw new Error('LIVE 상태의 테스트 방이 필요합니다.');
    }

    console.log(`테스트 대상 강연방 ID: ${room.roomId}`);
    console.log(`동시 청중: ${VUS}명`);
    console.log(`청중당 메시지: ${MESSAGES}건`);
    console.log(`예상 전체 전송: ${EXPECTED_SENT}건`);
    console.log(`예상 전체 수신: ${EXPECTED_RECEIVED}건`);

    return {
        roomId: room.roomId,

        // 모든 VU가 같은 전송 시작 시각을 사용한다.
        startAt: Date.now() + WARMUP_SECONDS * 1000,
    };
}

// STOMP 명령어, 헤더, 본문을 프레임으로 변환한다.
function stompFrame(command, headers = {}, body = '') {
    const lines = [
        command,
        ...Object.entries(headers).map(
            ([key, value]) => `${key}:${value}`
        ),
        '',
        '',
    ];

    return `${lines.join('\n')}${body}\x00`;
}

export default function (data) {
    const nickname = `k6-${RUN_ID}-${__VU}`;

    // 1. CSRF 토큰 발급
    const csrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`
    );

    if (!check(csrfResponse, {
        'CSRF 발급 성공': (r) => r.status === 200,
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
        '신규 청중 입장 성공': (r) => r.status === 201,
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
        '청중 세션 인증 성공': (r) => r.status === 200,
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

    // 4. STOMP 연결용 CSRF 토큰 발급
    const stompCsrfResponse = http.get(
        `${BASE_URL}/api/v1/auth/csrf`
    );

    if (!check(stompCsrfResponse, {
        'STOMP CSRF 발급 성공': (r) => r.status === 200,
    })) {
        return;
    }

    const stompCsrf = stompCsrfResponse.json('data');

    let connected = false;
    let subscribeSent = false;
    let completed = false;
    let connectionError = false;

    let sentCount = 0;
    let ownReceivedCount = 0;
    let duplicateCount = 0;
    let invalidCount = 0;

    // 송신자와 메시지 순번을 기준으로 중복 수신을 검사한다.
    const receivedKeys = new Set();

    // 서버가 발급한 메시지 ID의 중복도 확인한다.
    const receivedMessageIds = new Set();

    const expectedPerParticipant = VUS * MESSAGES;

    const endAt =
        data.startAt
        + MESSAGES * INTERVAL_SECONDS * 1000
        + DRAIN_SECONDS * 1000;

    // 5. WebSocket 연결
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

                    // STOMP 인증 완료 후 채팅 채널을 구독한다.
                    if (command === 'CONNECTED' && !connected) {
                        connected = true;

                        if (Date.now() >= data.startAt) {
                            connectionError = true;

                            console.error(
                                `VU ${__VU}: 연결 준비 시간 초과`
                            );

                            socket.close();
                            return;
                        }

                        socket.send(
                            stompFrame('SUBSCRIBE', {
                                id: 'chat',
                                destination:
                                    `/topic/rooms/${data.roomId}/chat`,
                                ack: 'auto',
                                'X-Chat-Role': 'PARTICIPANT',
                            })
                        );

                        socket.send(
                            stompFrame('SUBSCRIBE', {
                                id: 'errors',
                                destination: '/user/queue/errors',
                                ack: 'auto',
                            })
                        );

                        subscribeSent = true;

                        // 청중별 전송 시점을 분산한다.
                        const offset = Math.floor(
                            ((__VU - 1) / VUS)
                            * INTERVAL_SECONDS * 1000
                        );

                        // 6. 지정된 횟수만큼 채팅을 전송한다.
                        for (let seq = 1; seq <= MESSAGES; seq++) {
                            const sendAt =
                                data.startAt
                                + offset
                                + (seq - 1)
                                * INTERVAL_SECONDS * 1000;

                            socket.setTimeout(function () {
                                // 실행 ID, 송신자, 순번, 전송 시각을
                                // 메시지 본문에 포함한다.
                                const content =
                                    `k6-${RUN_ID}|${__VU}|${seq}|${Date.now()}`;

                                socket.send(
                                    stompFrame(
                                        'SEND',
                                        {
                                            destination:
                                                `/app/rooms/${data.roomId}/chat`,
                                            'content-type': 'application/json',
                                            'X-Chat-Role': 'PARTICIPANT',
                                            'X-Chat-Attempt': crypto.randomUUID(),
                                        },
                                        JSON.stringify({
                                            content: content,
                                        })
                                    )
                                );

                                sentCount++;
                                chatSent.add(1);

                            }, Math.max(1, sendAt - Date.now()));
                        }

                        // 마지막 메시지 전송 후에도 일정 시간 수신을 기다린다.
                        socket.setTimeout(function () {
                            completed = true;

                            socket.send(
                                stompFrame('DISCONNECT')
                            );

                            socket.close();

                        }, Math.max(1, endAt - Date.now()));
                    }

                    // 7. 서버에서 전달한 메시지를 검증한다.
                    if (command === 'MESSAGE') {
                        const separator = /\r?\n\r?\n/.exec(frame);

                        if (!separator) {
                            invalidCount++;
                            continue;
                        }

                        const body = frame.substring(
                            separator.index + separator[0].length
                        );

                        let event;

                        try {
                            event = JSON.parse(body);
                        } catch (error) {
                            invalidCount++;
                            continue;
                        }

                        // 개인 오류 채널에서 수신한 오류
                        if (event.type === 'ERROR') {
                            connectionError = true;

                            console.error(
                                `VU ${__VU}: 채팅 오류 ${event.code}`
                            );

                            continue;
                        }

                        if (event.type !== 'CHAT_CREATED') {
                            continue;
                        }

                        const chat = event.data;

                        // 이번 테스트에서 생성한 메시지만 확인한다.
                        if (
                            typeof chat?.content !== 'string'
                            || !chat.content.startsWith(`k6-${RUN_ID}|`)
                        ) {
                            continue;
                        }

                        const parts = chat.content.split('|');

                        const senderVu = Number(parts[1]);
                        const sequence = Number(parts[2]);
                        const sentAt = Number(parts[3]);
                        const latency = Date.now() - sentAt;

                        const expectedNickname =
                            `k6-${RUN_ID}-${senderVu}`;

                        // 강연방, 송신자, 순번, 메시지 ID를 검증한다.
                        const valid =
                            parts.length === 4
                            && chat.roomId === data.roomId
                            && chat.messageId > 0
                            && chat.sender?.type === 'PARTICIPANT'
                            && chat.sender?.name === expectedNickname
                            && chat.sender?.id > 0
                            && Number.isInteger(senderVu)
                            && senderVu >= 1
                            && senderVu <= VUS
                            && Number.isInteger(sequence)
                            && sequence >= 1
                            && sequence <= MESSAGES
                            && Number.isFinite(latency)
                            && latency >= 0
                            && (
                                senderVu !== __VU
                                || chat.sender.id === participant.participantId
                            );

                        if (!valid) {
                            invalidCount++;
                            continue;
                        }

                        const key = `${senderVu}-${sequence}`;

                        // 동일한 메시지를 두 번 수신했는지 검사한다.
                        if (
                            receivedKeys.has(key)
                            || receivedMessageIds.has(chat.messageId)
                        ) {
                            duplicateCount++;
                            continue;
                        }

                        receivedKeys.add(key);
                        receivedMessageIds.add(chat.messageId);

                        chatReceived.add(1);
                        chatBroadcastDuration.add(latency);

                        // 본인이 전송한 메시지도 수신하는지 확인한다.
                        if (senderVu === __VU) {
                            ownReceivedCount++;
                            chatOwnReceived.add(1);
                        }
                    }

                    if (command === 'ERROR') {
                        connectionError = true;

                        console.error(
                            `VU ${__VU}: STOMP 오류 발생`
                        );

                        socket.close();
                        return;
                    }
                }
            });

            socket.on('error', function () {
                connectionError = true;

                console.error(
                    `VU ${__VU}: WebSocket 오류 발생`
                );
            });

            // STOMP 인증 시간 초과 처리
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

    // 8. 최종 메시지 전송 및 수신 정합성을 검증한다.
    const success = check(response, {
        'WebSocket 핸드셰이크 성공': (r) =>
            r && r.status === 101,

        'STOMP 연결 성공': () =>
            connected,

        '채팅 및 오류 채널 구독 요청 완료': () =>
            subscribeSent,

        '지정된 테스트 시간 완료': () =>
            completed,

        '예상 메시지 전송 건수 일치': () =>
            sentCount === MESSAGES,

        '전체 메시지 수신 건수 일치': () =>
            receivedKeys.size === expectedPerParticipant,

        '본인 메시지 수신 건수 일치': () =>
            ownReceivedCount === MESSAGES,

        '다른 청중 메시지 수신 건수 일치': () =>
            receivedKeys.size - ownReceivedCount
            === (VUS - 1) * MESSAGES,

        '메시지 중복 수신 없음': () =>
            duplicateCount === 0,

        '잘못된 메시지 수신 없음': () =>
            invalidCount === 0,

        'WebSocket 및 STOMP 오류 없음': () =>
            !connectionError,
    });

    if (success) {
        chatSuccess.add(1);
    } else {
        console.error(
            `VU ${__VU}: `
            + `전송=${sentCount}/${MESSAGES}, `
            + `수신=${receivedKeys.size}/${expectedPerParticipant}, `
            + `중복=${duplicateCount}, `
            + `잘못된 메시지=${invalidCount}`
        );
    }
}