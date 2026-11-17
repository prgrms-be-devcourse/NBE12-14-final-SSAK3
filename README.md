# RE:ACT

청중의 질문과 반응이 강연에 반영되도록 돕는 실시간 소통 플랫폼. (백엔드 12기 14회차 · 3팀)

강연자가 강연방을 열면 청중이 QR 코드로 입장해 채팅·이해도·투표로 반응하고, 강연자는 대시보드에서 실시간으로 확인한다. 강연 종료 후에는 STT 발언과 청중 반응을 시간대별로 연결한 AI 회고 보고서를 제공한다.

Jira: https://ssakthree.atlassian.net/jira/software/projects/SCRUM/boards/1

## 구조

```
backend/src/main/java/com/react/
  global/        config, exception, response, security, websocket, util
  <도메인>/       controller, service, repository, domain, dto
                 도메인: auth, room, chat, feed, poll, stt, ai, dash, report
backend/src/test/java/com/react/<도메인>/

frontend/        React + Vite
docs/            planning.md(기획), feature-spec.md(기능 정의), api-spec.md(REST·STOMP 명세), db/(erd.dbml, 테스트 DB 준비), conventions.md, testing.md
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
배포(EC2): `docker compose -f docker-compose.yml up -d --build` (override 제외, DB·Redis 포트 비공개)

설정 파일: `application.yml`(공통), `application-local.yml`(로컬, 기본 프로파일), `application-prod.yml`(배포, 환경 변수로만 주입)

요구 사항: JDK 25, Node 22, Docker. Gradle 은 Wrapper(`backend/gradlew`)를 사용하므로 따로 설치하지 않습니다.

> 스키마는 서버 기동 시 Flyway(`backend/src/main/resources/db/migration`)가 자동 적용합니다. 이전 버전(CS매치)의 DB 볼륨이 남아 있으면 `docker compose down -v` 후 시작하세요.
