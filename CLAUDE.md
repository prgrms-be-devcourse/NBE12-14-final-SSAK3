# RE:ACT

청중의 질문·반응이 강연에 반영되도록 돕는 실시간 소통 서비스 (백엔드 12기 14회차 3팀).
강연자는 STT 로 발언을 기록하고, 청중은 QR 로 입장해 채팅·이해도(이해됨/헷갈림/모르겠음)·투표로 반응하며, AI 가 청중 의견을 주제별로 요약하고 강연 종료 후 회고 보고서를 만든다.

일정: 11/29 MVP 배포, 12/4 최종 배포, 12/7 최종 발표. Jira: https://ssakthree.atlassian.net/jira/software/projects/SCRUM/boards/1

> API 명세는 `docs/api-spec.md`, ERD 는 `docs/db/erd.dbml` 이다. 기획서·기능 정의서 원문은 팀 공유본을 기준으로 하고, 문서끼리 어긋나면 PR 에서 팀원과 조율한다.

## 기술 스택

- 백엔드: Java 25, Spring Boot 3.5.x, Gradle(Wrapper), JPA, Flyway, MySQL, Redis, Spring Security, WebSocket
- 프론트: React + Vite (TypeScript 전환은 SCRUM-98)
- 배포: AWS EC2 1대, Docker Compose, Nginx (REST·WSS 한 도메인)
- AI: LLM API 는 서버에서만 호출한다 (채팅 주기 분석, 종료 후 보고서 생성)
- 인증·실시간 방식(세션 쿠키, STOMP)은 API 명세 v1.1 제안이며 SCRUM-164 에서 확정한다.

## 명령어

```bash
cp .env.example .env && docker compose up -d mysql redis   # 인프라
cd backend && ./gradlew bootRun       # 실행 (JDK 25)
cd backend && ./gradlew build         # 빌드 + 테스트
cd frontend && npm install && npm run dev
```

## 구조

- 백엔드는 도메인 패키지: `com.react.<도메인>.{controller,service,repository,domain,dto}`
  - 도메인: auth, room, chat, feed(이해도), poll, stt, ai, dash, report
  - 공통: `com.react.global.{config,exception,response,security,websocket,util}`
- 테스트는 `backend/src/test/java/com/react/<도메인>/` 에 같은 구조로 둔다.
- 설정: `application.yml`(공통), `application-local.yml`(로컬), `application-prod.yml`(배포).
- DB 스키마의 기준은 Flyway 마이그레이션(`backend/src/main/resources/db/migration`)이며 `ddl-auto: validate` 다. 적용된 `V` 파일은 수정하지 않고 새 버전을 추가한다. 통합 테스트(`test` 프로파일)는 Flyway 를 끈다.

## 지켜야 할 것

- **Git:** `main`, `develop` 에 직접 push 하지 않는다. `develop` 에서 `타입/SCRUM-번호-설명` 브랜치를 따서 PR 한다. 커밋은 `타입: 내용 (SCRUM-번호)`, PR 제목은 `[타입] 내용`. 자세한 건 `docs/conventions.md`.
- **강연방 격리:** 채팅·이해도·투표·STT·AI 분석·보고서는 모두 강연방 단위로 저장·조회한다. 권한은 REST 와 WebSocket 모두 서버에서 검증하고, 클라이언트가 보낸 userId·participantId·역할을 믿지 않는다.
- **종료 후 차단:** ENDED 강연방은 새 채팅·이해도 변경·투표 응답을 받지 않는다.
- **AI:** 실패해도 채팅·이해도·투표는 계속 동작해야 한다. 같은 분석 구간은 한 번만 실행하고, 호출 횟수·입력량 상한을 둔다. 보고서 조회는 LLM 을 다시 부르지 않는다. AI 생성 내용과 실제 기록(STT 원문·채팅·집계)을 구분해 저장·표시하고, STT 누락 구간은 지어내지 않는다.
- **시크릿:** `.env`, API 키는 커밋·로그·응답에 남기지 않는다. 비밀번호는 `PasswordEncoder`(BCrypt) 로만 저장한다.
- **리뷰:** PR 은 최소 1명 승인 후 머지한다.
- **CI:** `.github/workflows/ci.yml` 이 PR·push 때 백엔드 빌드·테스트와 프론트 빌드를 돌린다. 머지 전 `./gradlew build` 를 먼저 돌려 둔다.
- 새 코드는 주변 코드의 이름·구조·주석 스타일을 따른다. 요청받지 않은 리팩터링·기능 추가는 하지 않는다.
