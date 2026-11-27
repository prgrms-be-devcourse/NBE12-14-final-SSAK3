# MySQL 테스트 실행

JDK 25와 실행 중인 MySQL 8.4가 필요하다. H2는 사용하지 않는다.

테스트는 개발 DB `react` 대신 `react_test`를 사용한다. 테스트 전용 계정 `react_test`에는 이 DB의 권한만 부여한다.

`test` 프로파일에서는 Flyway 자동 실행을 비활성화하고 Hibernate의 `ddl-auto`를 `none`으로 설정한다. 필요한 통합 테스트에서 Flyway 마이그레이션을 직접 실행하여 실제 MySQL 스키마를 사용한다.

테스트에 따라 Flyway clean/migrate로 스키마를 초기화하므로 테스트 DB에는 보관할 데이터를 넣지 않는다.

## 최초 준비

1. 저장소 루트에서 `docker compose up -d mysql redis`로 로컬 MySQL과 Redis를 실행한다.
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

전체 테스트를 다시 실행하려면 다음 명령을 사용한다.

```bash
./gradlew test --rerun-tasks --console=plain --no-daemon
```

테스트 결과는 `backend/build/reports/tests/test/index.html`에서 확인한다. 개별 테스트의 실행 결과와 개수는 `backend/build/test-results/test/`의 XML 파일에서 확인할 수 있다.

## 테스트 DB 및 운영 제한 설정

- 테스트 전용 DB를 사용하는 통합 테스트는 필요한 Flyway 마이그레이션을 직접 실행한다.
- 전체 강연방 수나 종료 후보 등 DB 전체 상태에 영향을 받는 테스트는 이전 테스트의 데이터가 결과에 영향을 주지 않도록 초기화한다.
- Flyway clean을 수행하기 전 실제 연결 DB가 테스트 전용 DB인지 확인한다.
- 동일한 `react_test`를 사용하는 Gradle 테스트와 IntelliJ 테스트를 동시에 실행하지 않는다.
- 병렬 실행이 필요한 경우 실행별 테스트 DB를 분리한다.

기존 기능 회귀 테스트에서는 운영 제한이 기존 테스트 시나리오에 영향을 주지 않도록 `application-test.yml`의 전체 LIVE 강연방 수, 강연자별 LIVE 강연방 수, 방별 등록 청중 수 제한을 `2147483647`로 설정한다.

운영 제한 전용 통합 테스트는 테스트 클래스의 `@SpringBootTest(properties = ...)`에서 실제 제한값을 별도로 적용한다. 기존 기능 회귀 테스트의 성공과 운영 제한 테스트의 성공은 서로 다른 검증 결과로 구분한다.

## 주요 API 통합 테스트

`backend/src/test/java/com/react/integration/`에 주요 기능을 연결하는 백엔드 통합 테스트를 둔다.

| 테스트 클래스 | 검증 내용 |
| --- | --- |
| `LectureFullFlowIntegrationTest` | 강연 생성, 청중 입장, 시작, STT, 채팅, 투표, AI 분석, 종료 및 보고서 연동 |
| `PollConcurrencyIntegrationTest` | 투표 응답 및 마감의 동시성, 집계 정합성 |
| `PollParticipantIntegrationTest` | 청중 입장과 투표 마감 시점의 참여 인원 정합성 |
| `ChatRecoveryIntegrationTest` | 채팅 전송 실패 및 재접속 시 메시지 복구 |
| `ChatBatchConcurrencyIntegrationTest` | 채팅 배치 대기열 정리와 신규 메시지 추가의 동시성 및 데이터 정합성 |
| `LectureFailureIsolationIntegrationTest` | AI 분석 실패, STT 인식 중단 및 복구 시 다른 강연 기능의 정상 동작 |

REST API, WebSocket/STOMP, MySQL을 백엔드 테스트 환경에서 연동하여 검증한다. 외부 AI API 응답은 테스트용 응답으로 대체한다.

채팅 배치 전송의 대기열 정리 과정과 메시지 추가가 동시에 발생해 실시간 전송이 누락되는 문제를 재현하는 테스트도 포함한다.

실행:

```bash
./gradlew test \
  --tests 'com.react.integration.*' \
  --rerun-tasks \
  --console=plain \
  --no-daemon
```

## 강연방 운영 제한 테스트

기존 기능 회귀 테스트와 별도로 운영 제한의 경계값, 동시성 및 자동 종료를 검증한다.

### 테스트 클래스

| 테스트 클래스 | 구분 | 테스트 메서드 수 |
| --- | --- | --- |
| `RoomOperationLimitsIntegrationTest` | MySQL·MockMvc 통합 테스트 | 12개 |
| `LectureRoomExpirySchedulerTest` | Mockito 단위 테스트 | 6개 |

### 주요 검증 항목

- 전체 LIVE 강연방 6개까지 시작 허용, 7번째 시작 거절
- 동일 강연자의 두 번째 LIVE 강연방 시작 거절
- 기존 강연 종료 후 새로운 강연 시작 허용
- 강연방별 신규 청중 50명 허용, 51번째 신규 입장 거절
- 정원 도달 후 기존 청중 재입장 및 동일 입장 키 재요청 허용
- 서로 다른 강연방의 동시 시작 및 같은 강연자의 동시 시작 제한
- 정원 직전 동시 청중 입장 시 등록 인원 제한
- 최대 강연 시간 180분 전후의 종료 조건 검증
- 자동 종료 시 기존 투표 및 STT 종료 처리 유지
- 수동 종료와 자동 종료의 경합 시 중복 종료 방지
- 특정 강연방의 자동 종료 실패 시 나머지 강연방 처리 유지
- 잘못된 최대 강연 시간 설정 검증

통합 테스트에서는 각 테스트 전 Flyway clean/migrate로 스키마를 초기화하여 다른 테스트에서 생성한 LIVE 강연방 및 등록 청중의 영향을 제거한다. 테스트 클래스 종료 후에는 빈 스키마를 복원한다.

180분 경과 조건은 실제로 기다리지 않고 DB의 `started_at`을 변경하여 검증한다.

자동 종료 스케줄러는 `test` 프로파일에서 비활성화되므로 통합 테스트에서는 실제 서비스를 연결한 스케줄러의 메서드를 직접 호출한다. 단위 테스트에서는 Mockito로 만료 시각 계산, 여러 방의 종료 처리 및 오류 격리를 검증한다.

이 방식은 자동 종료 로직을 검증하지만 실제 운영 프로파일의 주기적 스케줄러 실행 시간까지 검증하는 것은 아니다.

실행:

```bash
./gradlew test \
  --tests 'com.react.room.RoomOperationLimitsIntegrationTest' \
  --tests 'com.react.room.LectureRoomExpirySchedulerTest' \
  --rerun-tasks \
  --console=plain \
  --no-daemon
```

### 검증 기록

2026-11-27 로컬 실행 결과:

| 항목 | 결과 | 실행 시간 |
| --- | --- | --- |
| 운영 제한 테스트 지정 실행 | BUILD SUCCESSFUL | 54초 |
| 전체 백엔드 회귀 테스트 | BUILD SUCCESSFUL | 3분 57초 |

신규 테스트 클래스에 정의된 테스트는 총 18개다. 위 실행 기록의 개별 테스트 발견·실행 개수는 Gradle 테스트 결과 XML로 확인한다.

### Flyway 마이그레이션 검증

`FlywayMigrationTest`에서 V1부터 V18까지의 마이그레이션을 적용하고 현재 스키마를 검증한다.

V18에서는 `lecture_room_start_lock` 테이블과 강연방 시작 동시성 제어용 초기 잠금 행(id=1)을 추가한다. 기존 스키마 검증에도 해당 테이블과 초기 행을 반영한다.

```bash
./gradlew test \
  --tests 'com.react.global.db.FlywayMigrationTest' \
  --rerun-tasks \
  --console=plain \
  --no-daemon
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

## 부하 테스트

기능 통합 테스트와 실제 동시 사용자 처리 성능을 검증하는 부하 테스트는 구분한다.

초기 운영 목표는 최대 LIVE 강연방 6개, 강연방별 등록 청중 50명이다.

단일 강연방의 입장, WebSocket 연결 유지, 채팅 전송, 이해도 응답을 위한 기존 k6 스크립트와 보고서 동시 생성 부하 테스트를 사용한다.

현재 `infra/k6/chat.js`는 `CHAT_CREATED` 이벤트만 수신 결과로 처리하지만 서버는 `CHAT_BATCH` 이벤트를 발행한다. 따라서 이 스크립트의 채팅 수신·전달 지연 지표는 아직 유효한 검증 결과로 볼 수 없다.

- 기본 운영 제한에서는 방당 신규 청중을 최대 50명까지 등록할 수 있다.
- 반복 실행 시 신규 참여자가 누적되므로 테스트용 강연방을 새로 생성하거나 데이터를 격리한다.
- 운영 제한보다 큰 부하 실험은 격리된 환경에서 제한값을 조정한 후 수행한다.
- 정상 요청 실패와 의도적인 제한 초과 응답을 구분한다.
- 기존 단일 강연방 스크립트만으로 6개 강연방의 복합 부하를 검증했다고 판단하지 않는다.

부하 테스트 스크립트는 [k6 디렉터리](../infra/k6/), 보고서 동시 종료 테스트 방법은 [보고서 동시 종료 부하 테스트](../infra/k6/report-end.md), 모니터링 설정은 [로컬 모니터링 디렉터리](../infra/monitoring/)를 참고한다.

성능 및 안정성 목표는 [기능 정의서](feature-spec.md) §15 를 기준으로 한다.
