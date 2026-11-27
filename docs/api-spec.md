# RE:ACT — MVP API 명세서 v1.1

기준 문서는 `docs/feature-spec.md`(기능 정의서, P0), DB 기준은 `docs/db/erd.dbml`(V18 기준 22개 테이블)과 Flyway 마이그레이션이다.
문서끼리 어긋나면 DB 는 Flyway 마이그레이션을, 이 명세의 REST·WebSocket 형식을 우선하고, 실제 충돌은 PR 에서 팀원과 조율한다.

상태: 초기 개발 명세를 기준으로 구현 반영 중. 세션 쿠키·STOMP 인증 및 강연방별 권한 검증은 구현되었으며, 세부 연결 정책은 [공통 규약](api/common.md#1-5-stomp-인증인가-및-연결-수명)을 따른다. 강연방 운영 제한과 자동 종료 정책은 [강연방 API](api/room.md)를 따른다. 확정 정책과 후속 합의 사항은 §14를 참고한다.

도메인별 명세는 `docs/api/` 에 나뉘어 있다. 여러 도메인이 공유하는 계약(채팅 `sender` 형식, 이벤트 형식 등)은 한쪽 문서에만 정의하고 다른 문서에서는 링크한다.

| 문서 | 내용 | 담당 |
| --- | --- | --- |
| [common](api/common.md) | 개요, 공통 규약(기본 정보, 오류 응답, 인증·권한) | 전원 |
| [auth](api/auth.md) | AUTH-01~04 강연자 인증 | A |
| [room](api/room.md) | ROOM-01~08, ROOM-10 강연방·청중 입장·운영 제한·권한·강연자 홈 및 내 강연 기록 조회 | A |
| [chat](api/chat.md) | CHAT-01~05 실시간 채팅 | B |
| [poll](api/poll.md) | POLL-01~03, 05~07 실시간 투표·이해도 체크 | A·B |
| [stt](api/stt.md) | STT-01~05 발언 기록 | D |
| [ai-chat](api/ai-chat.md) | AI-CHAT-01~08 청중 의견 분석 | C |
| [dash](api/dash.md) | DASH-01~05 대시보드, STOMP 이벤트 | B(01~03)·C(04~05) |
| [report](api/report.md) | REPORT-01~09 회고 보고서·후속 답변 후보 | D |

## 11. P0 기능 정의서 ↔ API 대응표

`/api/v1` 은 생략했다.

| 기능 ID | API 또는 구현 방식 |
| --- | --- |
| AUTH-01~04 | POST `/auth/signup`, POST `/auth/login`, GET `/auth/me`, POST `/auth/logout` |
| 공통 인증 보안 | GET `/auth/csrf`, 세션 쿠키 및 REST·STOMP CSRF 검증 |
| ROOM-01 | POST `/rooms` |
| ROOM-02 | GET `/rooms/{roomId}/invitation` |
| ROOM-03 | GET `/rooms/{roomId}`, GET `/public/rooms/{joinCode}` |
| ROOM-04 | POST `/rooms/{roomId}/start`, POST `/rooms/{roomId}/end`, 서버 스케줄러의 자동 종료, 동시 LIVE 강연방 수 제한 |
| ROOM-05 | POST `/public/rooms/{joinCode}/participants`, GET `/rooms/{roomId}/participants/me`, 방별 등록 청중 정원 검증 및 재입장·멱등성 유지 |
| ROOM-06, 07 | REST 및 WebSocket 권한·종료 상태 검증 |
| ROOM-08 (P1) | GET `/rooms/home`, GET `/rooms/ready`, GET `/rooms/live`, GET `/rooms/history`, GET `/rooms/history/latest`, GET `/rooms/history/statuses` |
| ROOM-10 | DELETE `/rooms/{roomId}` (소유자의 준비 중인 방), 상태 채널의 `ROOM_DELETED` 이벤트 |
| CHAT-01, 02, 04, 05 | WebSocket 채팅 송수신·저장·검증·격리 |
| CHAT-03 | GET `/rooms/{roomId}/messages` |
| POLL-01 | POST `/rooms/{roomId}/polls`(`type`: GENERAL·UNDERSTANDING, 제한 시간), POST `/rooms/{roomId}/polls/{pollId}/close`, GET `/rooms/{roomId}/polls`, 서버 스케줄러의 자동 마감 |
| POLL-02 | PUT `/rooms/{roomId}/polls/{pollId}/responses/me` (마감 전 변경 가능, 마지막 선택이 최종 응답) |
| POLL-03 | GET `/rooms/{roomId}/polls/{pollId}/results` (HOST 만), 응답 중복 방지 |
| POLL-05 | 이해도 체크(`type=UNDERSTANDING`) 결과의 인원·비율·미응답 집계 |
| POLL-06 (P1) | GET `/rooms/{roomId}/polls/understanding-rounds` |
| POLL-07 (P1) | GET `/rooms/{roomId}/polls/{pollId}/responses/me` |
| STT-01 | POST 세션 시작 / POST 세션 종료 |
| STT-02, 03 | POST 확정 전사 저장 |
| STT-04 | POST 누락 시작 / PATCH 누락 종료 |
| STT-05 | GET STT 발언 및 누락 구간 |
| AI-CHAT-01~05, 08 | 내부 스케줄러·LLM 분석·저장·실패 처리 |
| AI-CHAT-06 | GET `/rooms/{roomId}/ai/analyses` |
| AI-CHAT-07, DASH-05 | GET 주제 그룹 관련 원문 조회 |
| DASH-01~04 | 기존 조회 API + WebSocket 이벤트 |
| REPORT-01 | 최초 접수 POST `/rooms/{roomId}/reports`, 일시적 생성 실패·전송 결과 미확정 시 소유자 수동 재시도 POST `/rooms/{roomId}/reports/retry` (한 번), OpenAI 결과 알림 POST `/webhooks/openai` |
| REPORT-02~05, 07 | 내부 데이터 수집·시간대 연결·LLM 초안 |
| REPORT-06 | 상태 조회 GET `/rooms/{roomId}/reports/status`, 본문 조회 GET `/rooms/{roomId}/reports` |
| REPORT-08·09 | 기존 보고서 본문의 `followUp`에 답변 확인이 필요한 질문과 검토용 보충 설명 초안·준비 방향 제공 |

---

## 12. 주요 통합 테스트 시나리오

| 테스트 | 검증 내용 |
| --- | --- |
| 전체 E2E | 회원가입 → 로그인 → 강연방 생성 → 참여 → STT → 채팅·이해도·투표 → AI 분석 → 강연 종료 → 회고 보고서 |
| 강연방 소유권 | 다른 강연자의 강연방에 대한 관리 요청 차단 |
| 청중 권한 | 다른 강연방의 채팅·이해도·투표 접근 차단 |
| 강연 상태 | READY 에서 채팅 차단, ENDED 에서 신규 응답 차단 |
| 전체 LIVE 강연방 제한 | 기본값 6개까지 허용, 7번째 시작 요청 거절 |
| 강연자별 LIVE 강연방 제한 | 같은 강연자의 두 번째 LIVE 강연방 시작 거절 |
| 청중 정원 | 방당 등록 청중 50명 허용, 51번째 신규 입장 거절 |
| 청중 재입장 | 정원 도달 후 유효한 기존 청중 및 동일 입장 키 재요청 허용 |
| 강연 시작 동시성 | 서로 다른 강연방 및 동일 강연자의 동시 시작 요청에서도 설정된 상한 유지 |
| 청중 입장 동시성 | 정원 직전 동시 신규 입장 요청에서도 등록 청중 상한 유지 |
| 최대 강연 시간 | 180분 미만인 방은 유지, 만료된 LIVE 강연방은 자동 종료 |
| 종료 동시성 | 수동·자동 종료 경합 시 중복 종료 방지, 투표 및 STT 종료 처리 유지 |
| 채팅 저장 | 강연자·청중 메시지 정상 저장 및 발신자 구분 |
| 채팅 복구 및 동시성 | 재접속 시 누락된 채팅 복구, 배치 대기열의 동시 추가·정리 시 메시지 유실 방지 |
| AI 분석 대상 | 강연자 메시지는 청중 의견 요약에서 제외 |
| 투표 응답 변경 | 마감 전 같은 청중이 선택을 바꾸면 응답은 1건이고 마지막 선택이 집계에 반영, 같은 선택 재요청은 `changed=false` |
| 투표 동시성 | 한 청중의 빠른 연속 변경·동시 요청 후 최종 선택과 집계 일치 |
| 투표 마감 | 제한 시간이 지나면 자동 마감되어 이후 응답 거부, 마감 직전 응답은 반영, 강연 종료 시 진행 중 투표 마감 |
| 이해도 체크 | `type=UNDERSTANDING` 선택지 고정, 선택하지 않고 마감되면 미응답으로 집계, 청중에게 결과 비공개 |
| 동시 투표 제한 | 진행 중인 투표가 있으면 새 투표(이해도 체크 포함) 생성 거부 |
| 투표 소속 검증 | 다른 투표의 선택지로 응답하는 요청 거부 |
| STT 저장 | 확정 발언 및 시간 기록 정상 저장 |
| STT 중복 전송 | 같은 segmentKey 재전송 시 중복 생성 없음 |
| STT 오류 | 인식 중단 시 누락 구간 생성·종료 |
| AI 원문 검증 | 없는 메시지·다른 강연 메시지·분석 구간 밖 메시지 참조 거절 |
| AI 장애 | LLM 실패해도 채팅·이해도·투표 정상 |
| 종료 직전 데이터 | 정상적으로 서버에 저장된 마지막 STT 발언이 회고 보고서에 포함되는지 검증 |
| 회고 사실 검증 | 실제 통계·STT 원문·AI 해석 구분 |
| 보고서 조회 | 완료된 보고서 조회 시 LLM 재호출 없음 |

운영 제한 테스트는 [테스트 안내](testing.md)를 따른다. 실제 운영 규모의 성능 검증 기준은 [기능 정의서](feature-spec.md) §15 를 따른다.

---

## 13. ERD ↔ API 정합성

| 항목 | 적용 내용 |
| --- | --- |
| 강연 시작 동시성 제어 | `lecture_room_start_lock`의 공통 잠금 행을 사용하여 전체·강연자별 LIVE 강연방 수 검증 |
| 강연 시간 제한 | `lecture_rooms.status`, `started_at` 기준으로 만료된 LIVE 강연방 조회 및 종료 |
| 청중 정원 | `lecture_participants.lecture_room_id` 기준 등록 청중 수 확인, 강연방 행 잠금으로 동시 입장 제어 |
| 강연자 채팅 | `chat_messages.host_user_id` 사용 |
| 청중 채팅 | `chat_messages.participant_id` 사용 |
| 채팅 중복 저장 | 강연방·작성자·`client_message_id` UNIQUE (식별자 없는 요청은 제외) |
| AI 청중 의견 | 강연자 채팅 제외 |
| 주제별 AI 요약 | `ai_topic_groups.topic`, `summary`, `category` |
| 원문 메시지 연결 | `ai_topic_group_messages` |
| AI 중복 분석 | 강연방·분석 시작·종료 구간 UNIQUE |
| STT 중복 저장 | 세션·segmentKey UNIQUE |
| STT 누락 이벤트 | `event_key` 및 UNIQUE |
| 투표 종류·마감 | `polls.type`, `closes_at`, `closed_at`, `close_reason` |
| 이해도 체크 선택지 | `poll_options.understanding_status` |
| 투표 최종 응답 | `poll_responses` (`(poll_id, participant_id)` UNIQUE, 변경 시 덮어씀) |
| 시간대별 회고 | 원천 데이터 집계 후 보고서 JSON 저장 |
| 보고서 중복 생성 | `lecture_reports.lecture_room_id` UNIQUE |
| QR 이미지 | 별도 DB 저장 없음 |

---

## 14. 주요 정책 및 후속 합의 필요 사항

인증 방식, STOMP 기본 규칙과 강연방 운영 제한은 구현되어 있다. 후속 합의가 필요한 정책은 기능별 명세와 팀 협의를 따른다.

| 항목 | 현재 제안 및 적용 정책 |
| --- | --- |
| 강연자 인증 | 세션 쿠키 |
| 청중 인증 | 비회원 참여 쿠키 |
| 실시간 통신 | WebSocket + STOMP |
| 강연방 상태 | READY → LIVE → ENDED |
| 강연 시작 전 입장 | READY 에서도 청중 입장 허용 |
| 강연방 운영 제한 | 전체·강연자별 LIVE 강연방, 등록 청중 정원 및 최대 강연 시간은 [기능 정의서](feature-spec.md) §15 기준 |
| 청중 재입장 | 유효한 기존 참여자 재사용, 정원 초과와 별개 처리 |
| 강연자 채팅 | 허용 |
| 채팅 길이 제한 | 현재 1,000자, 300자 변경 예정 |
| 채팅 전송 빈도 제한 | 사용자별 초당 2건 목표, 구현 예정 |
| AI 의견 요약 대상 | 청중 채팅만 |
| 이해도 비율 분모 | 누적 고유 청중 수 및 응답자 기준 비율 구분 |
| 투표 | 단일 선택, 마감 전 변경 가능, 마감 시 마지막 선택이 최종 응답 |
| 동시 투표 | 강연방당 OPEN 하나(이해도 체크 포함) |
| 투표 제한 시간 | 투표마다 강연자가 선택, 기본 60초(10~300초) |
| 투표 결과 공개 | 강연자만(진행 중에도 실시간) |
| STT | 브라우저 Web Speech API |
| 동시 STT 세션 | 강연방당 RECORDING 하나 |
| STT 저장 | 최종 확정 결과만 저장 |
| STT 중단 | 누락 구간 기록 |
| 자동 종료 직전 STT | 미전송 발언 저장 및 만료 안내 정책 후속 협의 |
| 실시간 AI 분석 주기 | 30초 |
| 보고서 시간 구간 | 60초 |
| AI 분석 중복 | 동일 구간 중복 실행 방지 |
| 보고서 생성 | 강연 종료 후 비동기 요청 |
| 보고서 저장 | 강연당 보고서 하나 |
| API 비용 제한 | P0 기본 호출량·입력량 제한 |
| STT·채팅 보존 기간 | 추후 확정 |

운영 제한 초과 오류는 [강연방 API](api/room.md), 설정 변경 방법은 [AWS 운영 배포 가이드](aws/deployment.md)를 따른다. 300명의 실제 동시 사용 성능은 별도 부하 테스트로 검증한다.

---

## 15. MVP 완료 기준

기능별 완료 기준은 `docs/feature-spec.md` §13 와 `docs/planning.md` §11 을 따른다. 요약:

- 강연자 인증, 강연방 생성·QR 입장·권한 구분·시작/종료·종료 후 응답 차단
- 전체·강연자별 LIVE 강연방 제한, 방별 청중 정원, 자동 종료, 경계값 및 동시성 검증
- 강연자·청중 실시간 채팅, 저장·조회·검증·강연방 격리
- 이해도 체크(투표 종류) 생성·응답·집계, 투표 생성·마감·응답 변경·자동 마감·중복 방지
- STT 시작·중지·저장·누락 구간·조회
- 주기적 AI 분석·주제별 요약·원문 연결·실패 처리
- 대시보드(채팅·이해도·투표·AI 요약·근거 원문)
- 강연 종료 후 AI 회고 보고서 생성·저장·조회, 실제 근거와 AI 생성 내용 구분, STT 누락의 임의 보완 금지
- 공개 URL, HTTPS/WSS, 환경변수 보안, 단위·통합 테스트, 강연방 간 격리 검증, 이해도·투표 정합성 검증, 10~20명 모의 강연, E2E
- 최대 운영 규모인 6개 강연방 × 50명 부하 테스트와 비기능 요구사항 검증
