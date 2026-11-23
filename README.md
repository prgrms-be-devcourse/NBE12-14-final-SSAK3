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

강연방을 종료하면 생성 상태가 표시됩니다. 완료 후 **회고 보고서 보기**를 누르면 해당 강연의 요약과 기록을 볼 수 있습니다. 가상 데이터로 화면을 살펴보려면 `/preview/reports`를 여세요. [보고서 화면 구성·실행 방법](docs/experiments/report-ui.md)

이전에 종료한 방은 강연자 메뉴의 **내 강연 기록**에서 다시 열 수 있습니다. 최근 강연을 10개씩 볼 수 있고, 완료된 회고 보고서도 같은 목록에서 열 수 있습니다.

배포(EC2): `main` 브랜치에 Push하면 GitHub Actions가 GHCR에 Docker 이미지를 게시하고 AWS SSM을 통해 EC2에 자동 배포합니다. 운영 환경에서는 소스 코드를 직접 빌드하지 않습니다.

자세한 배포 절차와 운영 명령어는 [AWS 운영 배포 가이드](docs/aws/deployment.md)를 참고하세요.

설정 파일: `application.yml`(공통), `application-local.yml`(로컬, 기본 프로파일), `application-prod.yml`(배포, 환경 변수로만 주입)

요구 사항: JDK 25, Node 22, Docker. Gradle 은 Wrapper(`backend/gradlew`)를 사용하므로 따로 설치하지 않습니다.

> 스키마는 서버 기동 시 Flyway(`backend/src/main/resources/db/migration`)가 자동 적용합니다.
>
> 이전 프로젝트의 DB 볼륨 때문에 로컬 개발 환경에서 초기화가 필요한 경우에만 `docker compose down -v`를 사용할 수 있습니다.
>
> **주의:** `docker compose down -v`는 Docker Compose가 관리하는 볼륨 데이터를 삭제합니다. 운영 EC2에서는 실행하지 마세요. 운영 MySQL 데이터가 삭제될 수 있습니다.