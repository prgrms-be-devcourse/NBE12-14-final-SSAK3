# 연습 모드 React 데모

첨부된 로비 디자인을 바탕으로 React 화면과 실제 백엔드 인증·연습 API를 연결했다. 네트워크 객관식 예시 문제 1개를 제공한다. 브라우저에서 채점하거나 가상 로그인하지 않는다.

## 데이터 분리

| 구성 | 일반 개발 환경 | 데모 환경 |
| --- | --- | --- |
| Compose 프로젝트 | 기존 프로젝트 | `csmatch-demo` |
| MySQL | `localhost:3306`, `csmatch` | `localhost:3307`, `csmatch_demo` |
| Redis | `localhost:6379` | `localhost:6380` |
| 백엔드 | `localhost:8080` | `localhost:8081`, `demo` 프로파일 |
| 프론트 | `localhost:5173` | `http://127.0.0.1:5174`, Vite `demo` 모드 |

기존 개발 DB나 배포 DB는 수정하지 않는다. 기본 Flyway `db/migration`에는 예시 문제를 추가하지 않는다. `application-demo.yml`만 `db/demo`를 추가로 읽어 `R__practice_example.sql`을 적용한다. 일반 실행·배포에서는 `demo` 프로파일을 활성화하지 않는다.

## 실행 (Windows PowerShell)

Docker Desktop, JDK 25, Node.js가 필요하다. 저장소 루트에서:

```powershell
.\scripts\start-demo-infra.ps1
```

최초 실행 시 루트 `.env.demo`에 별도의 무작위 DB 암호와 JWT 키를 만든다. 파일은 Git에서 제외된다. 기존 `.env`는 수정하지 않는다. 재실행해도 비밀번호와 DB 볼륨을 유지한다.

터미널 1:

```powershell
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=demo"
```

터미널 2:

```powershell
cd frontend
npm ci
npm run dev:demo
```

브라우저에서 `http://127.0.0.1:5174`를 연다. 새로운 데모용 이메일·닉네임·비밀번호로 가입한다. 실제 사용 중인 계정의 비밀번호는 사용하지 않는다.

1. 로비의 연습 모드 진입 → 회원가입 또는 로그인
2. 전체 또는 네트워크 선택 → 연습 시작
3. 보기 선택 → 답안 제출 → 서버의 정답·해설 확인
4. 결과 확인 → 다시 풀기 (전체 진도 초기화 확인)
5. 다른 답을 제출해 정답·오답 결과 확인

나머지 분야에는 문제가 없다는 안내가 표시된다. 새로고침해도 로그인한 탭에서 이어 풀 수 있다. 대결·프로필 상세·복습 목록은 준비 중이며 가상으로 동작하지 않는다.

## 검증

데모 서버가 실행 중인 상태에서:

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
npm run build
```

E2E 테스트는 5174의 데모 배지를 확인한 뒤 별도의 테스트 계정을 생성한다. 데스크톱·390px 화면, 실제 가입과 로그인, 문제 없는 분야, 정답·오답, 다시 풀기, 새로고침·이어 풀기, 중도 종료, 인증 만료와 제출 응답 유실 복구를 확인한다. 네트워크 trace는 토큰을 남기지 않도록 꺼져 있다. 캡처는 Git에서 제외된 `frontend/test-results`에 저장한다.

## 종료 및 재실행

두 서버 터미널에서 `Ctrl+C`를 누른 뒤 저장소 루트에서:

```powershell
docker compose --env-file .env.demo -p csmatch-demo -f compose.demo.yml stop
```

다음에는 실행 절차를 다시 따르면 된다. `.env.demo`는 데모 볼륨이 유지되는 동안 보관한다. 정식 데이터에 대한 `down -v`나 기존 수동 변경 SQL 실행은 필요하지 않다.

실제 기능 연동용 프론트는 `npm run dev`로 일반 백엔드 8080에 연결할 수 있다. 데모 배지와 예시 안내는 `demo` 모드에서만 표시된다.
