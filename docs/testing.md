# MySQL 테스트 실행

JDK 25와 실행 중인 MySQL 8.4가 필요하다. H2는 사용하지 않는다.

테스트는 개발 DB `react` 대신 `react_test`를 사용한다. 테스트 전용 계정 `react_test`에는 이 DB의 권한만 부여한다. 통합 테스트는 `create-drop`으로 테이블을 생성·삭제하므로 테스트 DB에 보관할 데이터를 넣지 않는다. `test` 프로파일은 Flyway를 끄므로 테스트 DB에는 마이그레이션 이력이 남지 않는다.

## 최초 준비

1. 저장소 루트에서 `docker compose up -d mysql`로 로컬 MySQL을 실행한다.
2. MySQL 관리자 연결에서 전용 계정을 생성한다. 아래 비밀번호 자리에는 새 테스트용 비밀번호를 지정한다.

   ```sql
   CREATE USER 'react_test'@'%' IDENTIFIED BY '<테스트 전용 비밀번호>';
   ```

3. 같은 관리자 연결에서 `docs/db/init-test-databases.sql`을 실행한다. 개발 DB를 수정하는 SQL은 포함하지 않는다.
4. 저장소 루트의 `.env.test`에 다음 값을 저장한다. 이 파일은 `.gitignore`의 `.env.*` 규칙으로 제외된다.

   ```properties
   TEST_MYSQL_PASSWORD=<위에서 정한 비밀번호>
   TEST_MYSQL_HOST=localhost
   TEST_MYSQL_PORT=3306
   ```

환경 변수로도 같은 값을 전달할 수 있다. 테스트 설정은 backend 작업 디렉터리를 기준으로 `../.env.test`를 읽는다.

## 실행

```bash
cd backend
./gradlew test
```

## 종료 후 AI 분석 후보 조회 테스트

`AiAnalysisFinalizationRepositoryIntegrationTest`는 전체 종료 방을 정렬하여 제한된 개수만 조회한다.
이전 실행이나 다른 테스트가 남긴 방이 후보를 차지하지 않도록, 각 테스트 전 Flyway clean/migrate로
스키마를 초기화하고 테스트 후에도 clean으로 정리한다. 실제 연결 DB 이름이 `react_test`인지
확인한 뒤에만 초기화한다. 운영 조회의 정렬·LIMIT 조건은 변경하지 않는다.

단독 재실행:

```bash
./gradlew test --tests com.react.ai.AiAnalysisFinalizationRepositoryIntegrationTest --rerun-tasks
```

이 방식은 저장소의 기존 공유 테스트 DB·순차 실행 방식에 맞춘 것이다. 같은 `react_test`를 사용하는
IntelliJ 테스트와 Gradle 테스트를 동시에 실행하지 않는다. 병렬 실행이 필요하면 실행별 DB를 분리해야 한다.

## 실제 서버와 브라우저를 연결하는 강연방 통합 테스트

각 시나리오는 테스트 계정과 방을 직접 만든다. STT 시각 테스트에서는 강연자 브라우저의 시계를 5분 느리게 설정하고 음성 인식 이벤트를 모의로 발생시킨다. 발언과 누락 시작·복구 요청을 실제 서버에 보낸 뒤 저장된 시각을 조회해 확인한다.

강연자와 청중이 참여하는 시나리오는 쿠키가 분리된 Chromium 창 두 개를 사용한다. 삭제 시나리오는 청중 창에 도착한 `ROOM_DELETED`의 방 ID, 자동 퇴장 안내, 삭제한 초대 링크의 재방문 결과를 검사한다. 정상 종료 시나리오는 같은 대기 창에서 `LIVE`와 `ENDED` 이벤트를 차례로 확인한다.

채팅 시나리오에서는 강연자가 보낸 첫 안내보다 늦게 청중이 입장한다. 두 창의 양방향 메시지와 강연자 표시, 청중의 입장 전 내역 제외, 재연결·새로고침 후 복구, 종료 후 강연자의 읽기 전용 기록을 확인한다.

보고서 시나리오는 PC에서 기록이 없는 방을, 모바일 Chromium에서는 STT API로 강사 발언 한 건을 저장한 방을 종료한다. 종료 화면이 상태만 조회하는지, 완료 후 보고서를 직접 열어 기록을 읽고 새로고침하거나 강연방으로 돌아갈 수 있는지 확인한다. 두 방 모두 외부 LLM 키 없이 실행할 수 있다. 자동 생성을 검증하려면 종료 분석 스케줄러가 실행되는 Spring 프로필을 사용한다(`test` 프로필에서는 비활성화된다).

PC·모바일 모두 **내 강연 기록**에서 방금 종료한 강연의 보고서를 열고, 브라우저 뒤로 가기로 목록에 돌아와 같은 강연의 종료 화면을 다시 연다. 여러 페이지의 위치 복원, 상태별 갱신과 오류 복구는 `frontend/tests/browser/room-history.spec.js`에서 응답을 제어해 검증한다.

1. 별도 MySQL 스키마와 Redis를 사용하는 Spring 서버를 `127.0.0.1:18080`에 실행한다. 서버에는 테스트 전용 DB 연결 정보와 `PARTICIPANT_JOIN_SECRET`을 설정한다. `ROOM_JOIN_BASE_URL`과 `CORS_ALLOWED_ORIGINS`는 모두 `http://127.0.0.1:15173`으로, 로컬 HTTP용 `SESSION_COOKIE_SECURE`는 `false`로 지정한다.
2. `frontend`에서 `npm ci`와 `npx playwright install chromium`으로 의존성을 준비한다.
3. `npx playwright test -c tests/integration/playwright.config.js`를 실행한다. 테스트가 15173 포트에 프론트 서버를 띄운다. 강연자·청중 채팅, 삭제 확인창, 청중 대기·퇴장 화면 캡처는 `frontend/playwright-report/room-integration/`에 저장된다.
