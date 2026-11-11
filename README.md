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
  api, auth, components, hooks, ws, utils, assets

docs/            api(명세), erd, decisions(결정 기록)
scripts/         보조 스크립트
.github/         CI, PR 템플릿
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
| SCRUM-81 인프라 | `docker-compose.yml`, `.github`, 각 Dockerfile |
| SCRUM-83 프론트 | `frontend/` |

## 시작하기

```bash
cp .env.example .env            # 값 채우기
docker compose up -d mysql redis

cd backend && gradle bootRun    # 또는 IDE에서 CsMatchApplication 실행
cd frontend && npm install && npm run dev   # http://localhost:5173
```

전체 실행(로컬): `docker compose up -d --build` (http://localhost)
배포(EC2): `docker compose -f docker-compose.yml up -d --build` (override 제외, DB·Redis 포트 비공개)

설정 파일: `application.yml`(공통), `application-local.yml`(로컬, 기본 프로파일), `application-prod.yml`(배포, 환경 변수로만 주입)

Gradle Wrapper는 포함하지 않았습니다. 필요하면 `cd backend && gradle wrapper --gradle-version 8.14` 후 커밋하세요.

## 아직 정해지지 않은 것 (Jira 기준)

- 스키마 적용 방식과 `devduel_schema.sql` 반영 (SCRUM-84) — 현재 `ddl-auto: none`
- DB 테스트 환경 H2 / Testcontainers (SCRUM-88) — 현재 H2 임시
- 브랜치·커밋 규칙, 패키지 구조, 코드 컨벤션
- 프론트 UI 라이브러리·JS/TS 여부 (SCRUM-98)
- Spring Boot 버전: 3.5.0 으로 잡아 둠, 빌드 전 최신 3.5.x 확인
