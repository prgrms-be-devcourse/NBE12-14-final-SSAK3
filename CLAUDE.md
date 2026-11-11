# CS매치

레이팅 기반 1:1 실시간 CS 객관식 퀴즈 대결 서비스 (백엔드 12기 14회차 3팀). 일정: 11/27 MVP 배포, 12/7 최종 발표.

작업 전에 읽을 문서:
- `docs/game-rules.md`: 게임 규칙·점수·레이팅 (단일 출처, **(미확정)** 항목은 임의로 정하지 말고 질문)
- `docs/conventions.md`: 브랜치·커밋·PR·Jira 규칙
- `README.md`: 폴더 구조, 실행 방법

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
- 설정: `application.yml`(공통), `-local`, `-prod`(환경 변수로만 주입). 스키마는 `ddl-auto: none`.

## 지켜야 할 것

- **Git:** `main`, `develop` 에 직접 push 하지 않는다. `develop` 에서 `타입/SCRUM-번호-설명` 브랜치를 따서 PR 한다. 커밋은 `타입: 내용 (SCRUM-번호)`, PR 제목은 `[타입] 내용`. 자세한 건 `docs/conventions.md`.
- **범위:** `docs/game-rules.md` 의 "범위 밖" 기능은 만들지 않는다.
- **정합성:** 점수·레이팅은 서버가 판정한다. 같은 요청 ID 는 한 번만 반영하고, 레이팅은 경기당 한 번만 정산한다. 경기 단위로 직렬 처리한다.
- **LLM 호출은 배치에서만.** 대결 중에는 호출하지 않는다.
- **시크릿:** `.env`, API 키, JWT 비밀값은 커밋·로그·응답에 남기지 않는다. 비밀번호는 `PasswordEncoder`(BCrypt) 로만 저장한다.
- **리뷰:** PR 은 최소 1명 승인 후 머지한다.
- **CI 없음:** `.github/workflows` 는 담당자 확정 전까지 만들지 않는다. 머지 전 `./gradlew build` 를 직접 돌린다.
- 새 코드는 주변 코드의 이름·구조·주석 스타일을 따른다. 요청받지 않은 리팩터링·기능 추가는 하지 않는다.
