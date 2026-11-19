# RE:ACT

첫 진입 `/`은 로그인 상태에 따라 `/login` 또는 `/host/rooms/new`로 이동합니다. STT 확인용 화면은 `/dev/stt`입니다.

강연자 인증 화면: `/login`, `/signup`에서 실제 인증 API와 연결합니다. [인증 화면 결정 사항·검증](docs/host-flow-screen.md)

강연방 생성·상세 화면: 프론트 실행 후 `/host/rooms/new`로 접속합니다. 로그인과 백엔드 실행이 필요합니다. [화면 동작·연동 범위](docs/room-screen.md)

AI 청중 질문·의견 요약: 개발 환경의 강연방 상세에서 `AI 요약 확인`을 누르거나 `/dev/rooms/{roomId}/ai`로 접속합니다. 실제 저장된 분석·근거 원문을 조회하며 로그인과 방 소유권이 필요합니다. [SCRUM-154·155 화면 정책·확인 방법](docs/ai-audience-screen.md)

청중의 질문과 반응이 강연에 반영되도록 돕는 실시간 소통 플랫폼. (백엔드 12기 14회차 · 3팀)

강연자가 강연방을 열면 청중이 QR 코드로 입장해 채팅·이해도·투표로 반응하고, 강연자는 대시보드에서 실시간으로 확인한다. 강연 종료 후에는 STT 발언과 청중 반응을 시간대별로 연결한 AI 회고 보고서를 제공한다.

Jira: https://ssakthree.atlassian.net/jira/software/projects/SCRUM/boards/1

## 구조

```
backend/src/main/java/com/react/
  global/        config, exception, response, security, websocket, util
  <도메인>/       controller, service, repository, domain, dto
                 도메인: auth, room, chat, poll, stt, ai, dash, report
backend/src/test/java/com/react/<도메인>/

frontend/        React + Vite
docs/            planning.md(기획), feature-spec.md(기능 정의), api-spec.md(API 목차)와 api/(도메인별 REST·STOMP 명세), db/(erd.dbml, 테스트 DB 준비), conventions.md, testing.md
docker-compose.yml   mysql, redis, backend, web(Nginx)
```

## 시작하기

```bash
cp .env.example .env            # 값 채우기
docker compose up -d mysql redis

cd backend && ./gradlew bootRun   # 또는 IDE에서 ReActApplication 실행 (JDK 25 필요)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

전체 실행(로컬): `docker compose up -d --build` (http://localhost)

보고서 화면 미리보기: 프론트 실행 후 `/preview/reports`에서 가상 보고서·생성 상태를 확인할 수 있습니다. 실제 API·LLM 호출은 없습니다. [SCRUM-171 화면 구성·실행 방법](docs/experiments/report-ui.md)
배포(EC2): `docker compose -f docker-compose.yml up -d --build` (override 제외, DB·Redis 포트 비공개)

설정 파일: `application.yml`(공통), `application-local.yml`(로컬, 기본 프로파일), `application-prod.yml`(배포, 환경 변수로만 주입)

요구 사항: JDK 25, Node 22, Docker. Gradle 은 Wrapper(`backend/gradlew`)를 사용하므로 따로 설치하지 않습니다.

> 스키마는 서버 기동 시 Flyway(`backend/src/main/resources/db/migration`)가 자동 적용합니다. 이전 버전(CS매치)의 DB 볼륨이 남아 있으면 `docker compose down -v` 후 시작하세요.
