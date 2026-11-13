# CS매치

CS 퀴즈를 상대와 실시간으로 겨루는 1:1 개발 지식 대결 서비스. (백엔드 12기 14회차 · 3팀)

Jira: https://ssakthree.atlassian.net/jira/software/projects/SCRUM/boards/1

## 구조

```
backend/src/main/java/com/csmatch/
  global/        config, exception, response, security, websocket, util
  <도메인>/       controller, service, repository, domain(엔티티), dto
                 도메인: auth, user, rating, matchmaking, match, question,
                        review, report, practice, admin
backend/src/test/java/com/csmatch/<도메인>/

frontend/src/
  pages/         auth, lobby, matching, match, result, profile, practice, admin
  api, components, ws   (hooks, utils 등은 필요할 때 추가)

docs/            api(명세), erd, game-rules.md(게임 규칙), conventions.md(협업 규칙)
.github/         PR 템플릿
docker-compose.yml   mysql, redis, backend, web(Nginx)
```

폴더 구조는 시작용 제안입니다. 팀 합의(SCRUM-84)에 따라 자유롭게 바꾸세요.

| Jira 에픽 | 위치 |
| --- | --- |
| SCRUM-35 회원·인증 | backend `auth`, `user` |
| SCRUM-36 프로필·레이팅 | backend `user`, `rating` |
| SCRUM-37 매칭 | backend `matchmaking` |
| SCRUM-38 대결 WebSocket | backend `match` |
| SCRUM-39 연습 모드 | backend `practice` |
| SCRUM-40 결과·복습 | backend `match`, `review` |
| SCRUM-41 문제 오류 신고 | backend `report` |
| SCRUM-42 관리자 | backend `admin` |
| SCRUM-82 문제 생성·출제 | backend `question` |
| SCRUM-81 인프라 | `docker-compose.yml`, 각 Dockerfile |
| SCRUM-83 프론트 | `frontend/` |

## 시작하기

```bash
cp .env.example .env            # 값 채우기
docker compose up -d mysql redis

cd backend && ./gradlew bootRun   # 또는 IDE에서 CsMatchApplication 실행 (JDK 25 필요)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

전체 실행(로컬): `docker compose up -d --build` (http://localhost)
배포(EC2): `docker compose -f docker-compose.yml up -d --build` (override 제외, DB·Redis 포트 비공개)

설정 파일: `application.yml`(공통), `application-local.yml`(로컬, 기본 프로파일), `application-prod.yml`(배포, 환경 변수로만 주입)

요구 사항: JDK 25, Node 22, Docker. Gradle 은 Wrapper(`backend/gradlew`)를 사용하므로 따로 설치하지 않습니다.

## 아직 정해지지 않은 것 (Jira 기준)

- 스키마 적용 방식은 Flyway 로 확정 (SCRUM-112) — `backend/src/main/resources/db/migration`, 자세한 건 `docs/db/README.md`
- DB 테스트 환경 H2 / Testcontainers (SCRUM-88) — 현재 H2 임시
- 코드 컨벤션 (브랜치·커밋·PR 규칙은 `docs/conventions.md` 에 확정)
- 프론트 UI 라이브러리·JS/TS 여부 (SCRUM-98)
- CI: `.github/workflows/ci.yml` (PR·push 때 백엔드 빌드·테스트, 프론트 빌드)

## 문서

- React 연습 데모 실행: `docs/practice-demo.md` (기존 DB와 분리된 예시 문제 1개)
- 연습 백엔드 설계·검증: `docs/practice-design.md`
- 기존 DB의 연습 스키마 변경 적용: `docs/db/migrations/README.md` (새 서버 실행 전에 적용)
- 협업 규칙(브랜치·커밋·PR·Jira): `docs/conventions.md`
- 게임 규칙·점수·레이팅: `docs/game-rules.md`
- AI 도구(Claude Code 등) 사용 시: 루트의 `CLAUDE.md` / `AGENTS.md`
