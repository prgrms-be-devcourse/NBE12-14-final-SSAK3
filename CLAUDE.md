# CS매치

레이팅 기반 1:1 실시간 CS 객관식 퀴즈 대결 서비스 (백엔드 12기 14회차 3팀). 일정: 11/27 MVP 배포, 12/7 최종 발표.

작업 전에 읽을 문서:
- `docs/game-rules.md`: 게임 규칙·점수·레이팅
- `docs/conventions.md`: 브랜치·커밋·PR·Jira 규칙
- `README.md`: 폴더 구조, 실행 방법
- DB 스키마: `docs/db/schema.sql` (ERD 기반 SQL)
- API 명세(REST·WebSocket): `docs/api-spec.md`
- ERD: https://dbdiagram.io/d/%EC%8B%B9%EC%93%B0%EB%A6%AC-6af3e06d063ad9ee168b99b8
- API 명세 원본(REST·WebSocket): https://claude.ai/artifact/VGMk2169cgJ9CYqfTdnmGn

문서끼리 어긋나거나 정해지지 않은 값이 있을 때의 원칙:
- DB 테이블·컬럼·제약조건은 `docs/db/schema.sql`을 따른다.
- 게임 규칙·점수·레이팅은 `docs/game-rules.md`를 따른다.
- REST API 및 WebSocket 메시지 형식은 `docs/api-spec.md`를 따른다.
- 문서 간 실제 충돌은 PR에서 공유하고 팀원과 조율한다.
- `[미정]` 항목은 기존 팀 규칙에 따라 개발자가 결정하고 PR 본문 "리뷰어에게"에 기록한다.
- 확정된 게임 규칙과 범위 밖 기능은 임의로 변경하지 않는다.

## 기술 스택

- 백엔드: Java 25, Spring Boot 3.5.x, Gradle(Wrapper), JPA, MySQL, Redis, 순수 WebSocket(STOMP 없음, JSON 메시지), JWT, Cloudinary(프로필 이미지)
- 프론트: React 18 + Vite, react-router-dom 6
- 배포: AWS EC2 1대, Docker Compose, Nginx (REST·WSS 한 도메인)
- 문제 생성: 외부 LLM API (배치에서만 호출)

## 명령어

```bash
cp .env.example .env && docker compose up -d mysql redis   # 인프라
cd backend && ./gradlew bootRun       # 실행 (JDK 25)
cd backend && ./gradlew build         # 빌드 + 테스트
cd frontend && npm install && npm run dev
```

## 구조

- 백엔드는 도메인 패키지: `com.csmatch.<도메인>.{controller,service,repository,domain,dto}`
  - 도메인: auth, user, rating, matchmaking, match, question, review, report, practice, admin
  - 공통: `com.csmatch.global.{config,exception,response,security,websocket,util}`
- 테스트는 `backend/src/test/java/com/csmatch/<도메인>/` 에 같은 구조로 둔다.
- 설정: `application.yml`(공통), `application-local.yml`(로컬), `application-prod.yml`(배포).
- JPA 스키마 관리:
  - 공통: `ddl-auto: validate`
  - 로컬: `ddl-auto: update`
  - 배포: `ddl-auto: validate`
- DB 스키마의 기준은 `docs/db/schema.sql`이며, 배포 환경에서는 Hibernate가 테이블을 자동 수정하지 않는다.

## 지켜야 할 것

- **Git:** `main`, `develop` 에 직접 push 하지 않는다. `develop` 에서 `타입/SCRUM-번호-설명` 브랜치를 따서 PR 한다. 커밋은 `타입: 내용 (SCRUM-번호)`, PR 제목은 `[타입] 내용`. 자세한 건 `docs/conventions.md`.
- **범위:** `docs/game-rules.md` 의 "범위 밖" 기능은 만들지 않는다.
- **정합성:** 점수·레이팅은 서버가 판정한다. 같은 요청 ID 는 한 번만 반영하고, 레이팅은 경기당 한 번만 정산한다. 경기 단위로 직렬 처리한다.
- **LLM 호출은 배치에서만.** 대결 중에는 호출하지 않는다.
- **시크릿:** `.env`, API 키, JWT 비밀값은 커밋·로그·응답에 남기지 않는다. 비밀번호는 `PasswordEncoder`(BCrypt) 로만 저장한다.
- **리뷰:** PR 은 최소 1명 승인 후 머지한다.
- **CI:** `.github/workflows/ci.yml` 이 PR·push 때 백엔드 빌드·테스트와 프론트 빌드를 돌린다. 머지 전 `./gradlew build` 를 먼저 돌려 둔다.
- 새 코드는 주변 코드의 이름·구조·주석 스타일을 따른다. 요청받지 않은 리팩터링·기능 추가는 하지 않는다.
