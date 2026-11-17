# RE:ACT — MVP API 명세서 v1.1

기준 문서는 `docs/feature-spec.md`(기능 정의서, P0), DB 기준은 `docs/db/erd.dbml`(ERD v1.1, 16개 테이블)과 Flyway 마이그레이션이다.
문서끼리 어긋나면 DB 는 Flyway 마이그레이션을, 이 명세의 REST·WebSocket 형식을 우선하고, 실제 충돌은 PR 에서 팀원과 조율한다.

상태: 초기 개발 명세. 인증·실시간 프로토콜(세션 쿠키, STOMP)과 일부 정책은 팀 합의가 필요하다(§14).

도메인별 명세는 `docs/api/` 에 나뉘어 있다. 여러 도메인이 공유하는 계약(채팅 `sender` 형식, 이벤트 형식 등)은 한쪽 문서에만 정의하고 다른 문서에서는 링크한다.

| 문서 | 내용 | 담당 |
| --- | --- | --- |
| [common](api/common.md) | 개요, 공통 규약(기본 정보, 오류 응답, 인증·권한) | 전원 |
| [auth](api/auth.md) | AUTH-01~04 강연자 인증 | A |
| [room](api/room.md) | ROOM-01~07 강연방·청중 입장·권한 | A |
| [chat](api/chat.md) | CHAT-01~05 실시간 채팅 | B |
| [feed](api/feed.md) | FEED-01~06 이해도 신호 | B |
| [poll](api/poll.md) | POLL-01~03 실시간 투표 | A |
| [stt](api/stt.md) | STT-01~05 발언 기록 | D |
| [ai-chat](api/ai-chat.md) | AI-CHAT-01~08 청중 의견 분석 | C |
| [dash](api/dash.md) | DASH-01~05 대시보드, STOMP 이벤트 | B(01~03)·C(04~05) |
| [report](api/report.md) | REPORT-01~07 회고 보고서 | D |

## 11. P0 기능 정의서 ↔ API 대응표

`/api/v1` 은 생략했다.

| 기능 ID | API 또는 구현 방식 |
| --- | --- |
| AUTH-01~04 | POST `/auth/signup`, POST `/auth/login`, GET `/auth/me`, POST `/auth/logout` |
| ROOM-01 | POST `/rooms` |
| ROOM-02 | GET `/rooms/{roomId}/invitation` |
| ROOM-03 | GET `/rooms/{roomId}`, GET `/public/rooms/{joinCode}` |
| ROOM-04 | POST `/rooms/{roomId}/start`, POST `/rooms/{roomId}/end` |
| ROOM-05 | POST `/public/rooms/{joinCode}/participants` |
| ROOM-06, 07 | REST 및 WebSocket 권한·종료 상태 검증 |
| CHAT-01, 02, 04, 05 | WebSocket 채팅 송수신·저장·검증·격리 |
| CHAT-03 | GET `/rooms/{roomId}/messages` |
| FEED-01, 02, 03, 05 | PUT/GET `/rooms/{roomId}/understanding/me`, 이해도 변경 트랜잭션에서 이력 저장 |
| FEED-04, 06 | GET `/rooms/{roomId}/understanding/summary` |
| POLL-01 | POST 투표 생성 / POST 투표 종료 / GET 투표 목록 |
| POLL-02 | POST 투표 응답 |
| POLL-03 | GET 투표 결과 / 응답 중복 방지 |
| STT-01 | POST 세션 시작 / POST 세션 종료 |
| STT-02, 03 | POST 확정 전사 저장 |
| STT-04 | POST 누락 시작 / PATCH 누락 종료 |
| STT-05 | GET STT 발언 및 누락 구간 |
| AI-CHAT-01~05, 08 | 내부 스케줄러·LLM 분석·저장·실패 처리 |
| AI-CHAT-06 | GET `/rooms/{roomId}/ai/analyses` |
| AI-CHAT-07, DASH-05 | GET 주제 그룹 관련 원문 조회 |
| DASH-01~04 | 기존 조회 API + WebSocket 이벤트 |
| REPORT-01 | POST `/rooms/{roomId}/reports` |
| REPORT-02~05, 07 | 내부 데이터 수집·시간대 연결·LLM 초안 |
| REPORT-06 | GET `/rooms/{roomId}/reports` |

---

## 12. 주요 통합 테스트 시나리오

| 테스트 | 검증 내용 |
| --- | --- |
| 전체 E2E | 회원가입 → 로그인 → 강연방 생성 → 참여 → STT → 채팅·이해도·투표 → AI 분석 → 강연 종료 → 회고 보고서 |
| 강연방 소유권 | 다른 강연자의 강연방에 대한 관리 요청 차단 |
| 청중 권한 | 다른 강연방의 채팅·이해도·투표 접근 차단 |
| 강연 상태 | READY 에서 채팅 차단, ENDED 에서 신규 응답 차단 |
| 채팅 저장 | 강연자·청중 메시지 정상 저장 및 발신자 구분 |
| AI 분석 대상 | 강연자 메시지는 청중 의견 요약에서 제외 |
| 이해도 정합성 | 같은 상태 재요청 시 이력·집계 중복 없음 |
| 이해도 동시성 | 빠른 상태 변경 후 최신 상태와 이력 일치 |
| 투표 정합성 | 동일 참가자의 중복 응답 거부 |
| 투표 소속 검증 | 다른 투표의 선택지로 응답하는 요청 거부 |
| STT 저장 | 확정 발언 및 시간 기록 정상 저장 |
| STT 중복 전송 | 같은 segmentKey 재전송 시 중복 생성 없음 |
| STT 오류 | 인식 중단 시 누락 구간 생성·종료 |
| AI 원문 검증 | 없는 메시지·다른 강연 메시지·분석 구간 밖 메시지 참조 거절 |
| AI 장애 | LLM 실패해도 채팅·이해도·투표 정상 |
| 종료 직전 데이터 | 마지막 STT 발언이 회고 보고서에 포함 |
| 회고 사실 검증 | 실제 통계·STT 원문·AI 해석 구분 |
| 보고서 조회 | 완료된 보고서 조회 시 LLM 재호출 없음 |

---

## 13. ERD ↔ API 정합성

| 항목 | 적용 내용 |
| --- | --- |
| 강연자 채팅 | `chat_messages.host_user_id` 사용 |
| 청중 채팅 | `chat_messages.participant_id` 사용 |
| AI 청중 의견 | 강연자 채팅 제외 |
| 주제별 AI 요약 | `ai_topic_groups.topic`, `summary`, `category` |
| 원문 메시지 연결 | `ai_topic_group_messages` |
| AI 중복 분석 | 강연방·분석 시작·종료 구간 UNIQUE |
| STT 중복 저장 | 세션·segmentKey UNIQUE |
| STT 누락 이벤트 | `event_key` 및 UNIQUE |
| 이해도 최신 상태 | `participant_understandings` |
| 이해도 변경 이력 | `understanding_histories` |
| 투표 중복 방지 | `(poll_id, participant_id)` UNIQUE |
| 시간대별 회고 | 원천 데이터 집계 후 보고서 JSON 저장 |
| 보고서 중복 생성 | `lecture_reports.lecture_room_id` UNIQUE |
| QR 이미지 | 별도 DB 저장 없음 |

---

## 14. 개발 전 팀 합의 필요 사항

| 항목 | 현재 제안 |
| --- | --- |
| 강연자 인증 | 세션 쿠키 |
| 청중 인증 | 비회원 참여 쿠키 |
| 실시간 통신 | WebSocket + STOMP |
| 강연방 상태 | READY → LIVE → ENDED |
| 강연 시작 전 입장 | READY 에서도 청중 입장 허용 |
| 강연자 채팅 | 허용 |
| AI 의견 요약 대상 | 청중 채팅만 |
| 이해도 비율 분모 | 누적 고유 청중 수 |
| 투표 | 단일 선택, 재응답 불가 |
| 동시 투표 | 강연방당 OPEN 하나 |
| STT | 브라우저 Web Speech API |
| STT 저장 | 최종 확정 결과만 저장 |
| STT 중단 | 누락 구간 기록 |
| 실시간 AI 분석 주기 | 30초 |
| 보고서 시간 구간 | 60초 |
| AI 분석 중복 | 동일 구간 중복 실행 방지 |
| 보고서 생성 | 강연 종료 후 비동기 요청 |
| 보고서 저장 | 강연당 보고서 하나 |
| API 비용 제한 | P0 기본 호출량·입력량 제한 |
| STT·채팅 보존 기간 | 추후 확정 |

---

## 15. MVP 완료 기준

기능별 완료 기준은 `docs/feature-spec.md` §14 와 `docs/planning.md` §11 을 따른다. 요약:

- 강연자 인증, 강연방 생성·QR 입장·권한 구분·시작/종료·종료 후 응답 차단
- 강연자·청중 실시간 채팅, 저장·조회·검증·강연방 격리
- 이해도 선택·변경·이력·집계, 투표 생성·종료·응답·중복 방지
- STT 시작·중지·저장·누락 구간·조회
- 주기적 AI 분석·주제별 요약·원문 연결·실패 처리
- 대시보드(채팅·이해도·투표·AI 요약·근거 원문)
- 강연 종료 후 AI 회고 보고서 생성·저장·조회, 실제 근거와 AI 생성 내용 구분, STT 누락의 임의 보완 금지
- 공개 URL, HTTPS/WSS, 환경변수 보안, 단위·통합 테스트, 강연방 간 격리 검증, 이해도·투표 정합성 검증, 10~20명 모의 강연, E2E
