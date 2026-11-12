# SCRUM-55 WebSocket 연결 기반

## 구현 범위

- 순수 WebSocket `/ws` 연결, JSON 메시지. STOMP를 사용하지 않는다.
- 연결 후 10초 이내 AUTH 완료. 인증 전 AUTH 이외의 메시지는 거절한다.
- 검증된 사용자 ID로 연결을 관리하며 인증에 성공한 새 연결이 기존 연결을 교체한다.
- 클라이언트 PING 간격 5초, 마지막 유효한 PING 이후 15초 동안 신호가 없으면 종료한다. 최초 기준은 인증 성공 시각이다.
- 종료 스케줄러는 1초 간격으로 검사한다. 지연된 스케줄러 검사 전이라도 10초 이후 AUTH, 15초 이후 PING으로 연결을 살릴 수 없다.
- 만료 계산은 단조 증가 시간을 사용하고 응답 시각은 서버 epoch milliseconds를 사용한다.
- 동시에 발생하는 서버 전송은 `ConcurrentWebSocketSessionDecorator`로 직렬화한다. 전송 제한 5초, 버퍼 64 KiB, 수신 메시지 제한 16 KiB이다.

## 메시지

연결은 `ws://localhost:8080/ws` 또는 배포 환경의 `wss://{도메인}/ws`로 한다. 토큰을 URL에 넣지 않는다.

```json
{"type":"AUTH","payload":{"accessToken":"<access-token>"}}
```

검증에 성공하면 다음 구조로 응답한다. `userId`는 클라이언트 입력이 아닌 검증 결과다.

```json
{"type":"AUTH_SUCCESS","payload":{"userId":7,"serverTime":1794441600000,"pingIntervalSec":5,"heartbeatTimeoutSec":15}}
```

```json
{"type":"PING","payload":{}}
```

```json
{"type":"PONG","payload":{"serverTime":1794441605000}}
```

현재는 AUTH/PING만 지원한다. 경기 메시지 핸들러는 후속 이슈에서 추가한다. 정상 JSON이라도 미지원 타입, 반복 AUTH, 비어 있지 않은 PING payload는 종료 처리한다. WebSocket 프로토콜 자체의 ping/pong 프레임은 위 애플리케이션 JSON PING을 대신하지 않는다.

## 종료

| 코드 | 사유 | 의미 |
| --- | --- | --- |
| 1008 | AUTH_REQUIRED | 인증 전 다른 메시지 수신 |
| 1008 | AUTH_FAILED | 잘못된 토큰·검증기 미연결·인증 제한 시간 경과 등 |
| 1008 | INVALID_MESSAGE / UNSUPPORTED_MESSAGE / TEXT_ONLY | 메시지 형식 또는 타입 위반 |
| 4000 | AUTH_TIMEOUT | 인증 완료 없이 10초 경과 |
| 4000 | HEARTBEAT_TIMEOUT | 유효한 PING 없이 15초 경과 |
| 1008 | HEARTBEAT_TIMEOUT | 만료 후 다음 검사 전에 PING 수신 |
| 4002 | CONNECTION_REPLACED | 같은 사용자의 새 연결이 인증됨 |
| 1011 | 서버 오류 | 전송 오류 등 |

인증 실패 상세 사유, 토큰, 메시지 본문은 로그나 응답에 포함하지 않는다.

## 인증 담당 연결 방법 — 미완료

인증 모듈에서 `AccessTokenVerifier` 구현을 Spring Bean으로 제공한다. 정상 액세스 토큰일 때만 양수 사용자 ID를 반환하고, 실패하면 `OptionalLong.empty()`를 반환한다. 서명·만료·발급자·대상·액세스/리프레시 구별·회원 상태 등의 실제 검증 계약은 인증 담당과 맞춘다. JWT를 단순 디코딩한 ID를 반환하면 안 된다.

현재 실제 검증 Bean은 없다. 기본 실행에서는 모든 AUTH가 실패하는 것이 의도한 동작이다. `src/test`의 가짜 검증기로만 연결 성공 흐름을 검증한다. REST 로그인 구현 시 `WebSocketSecurityConfiguration`의 임시 HTTP 보안 체인을 인증 담당 체인과 통합한다. `/ws`의 GET만 인증 없이 핸드셰이크를 허용하며 그 외 HTTP 경로 보호를 유지한다.

브라우저 Origin은 기본적으로 동일 Origin만 허용한다. Vite 개발 서버 등 별도 Origin이 필요하면 `app.websocket.allowed-origins`에 허용할 정확한 Origin을 지정한다(예: `http://localhost:5173`). 와일드카드로 전체 허용하지 않는다.

## 경기 담당 연결 방법 — 미완료

현재 연결이 종료되면 `WebSocketDisconnected(userId, sessionId, reason)` 이벤트를 발행한다. 옛 연결의 늦은 종료 콜백이 새 연결을 지우거나 중복 종료 이벤트를 발생시키지 않는다. 교체 자체는 사용자 연결 끊김으로 알리지 않는다.

이벤트는 접속 상태의 사실을 알리는 연결 지점이며 `OPPONENT_STATUS` 메시지를 직접 보내지 않는다. 경기 모듈에서 서버의 경기 참여자 정보를 조회한 뒤 실제 상대에게 `WebSocketConnections.sendToUser`로 보낸다. 클라이언트가 제출한 상대 ID를 신뢰하지 않는다. 이벤트 처리와 재접속이 경합할 수 있으므로 경기 모듈은 sessionId와 최신 연결 상태를 함께 고려해야 한다. 비동기 이벤트 소비 시 특히 과거 종료를 새 연결 종료로 적용하지 않아야 한다.

경기 상태 조회·상대 알림·RESUME·무효 경기 처리·점수 및 레이팅 정산은 구현하지 않았다. SCRUM-55의 실제 JWT 인증 및 상대 알림 완료 조건은 아직 미완료다.

## 검증

```powershell
cd backend
./gradlew.bat build --no-daemon
```

단위 테스트는 실제 대기 대신 제어 가능한 시간을 사용한다. 인증 제한 시간, 마지막 PING 기준 만료, 토큰 거절, 검증기 예외, 연결 교체, 늦은 종료 콜백, 전송 오류, 동시 전송을 검증한다. 통합 테스트는 임베디드 서버에 실제 WebSocket 연결을 열어 AUTH/PING, 인증 전 거절, 외부 Origin 거절과 HTTP 경로 보호를 확인한다. 기본 컨텍스트 테스트는 실제 JWT 검증기가 없을 때 인증이 거절되는지 확인한다.

Mockito는 `mock-maker-subclass`를 사용한다. Windows 한글 경로에서 inline mock maker의 동적 Java agent 로딩이 실패했고, 현재 테스트는 인터페이스 모킹만 필요하기 때문이다. 이 설정은 final 클래스·static 메서드 모킹을 지원하지 않는다. Java 25 환경에서 빌드 및 테스트 27개 통과를 확인했다. 실행 중 의존성의 Unsafe 및 agent 관련 경고는 남아 있으나 테스트 실패는 없다.
