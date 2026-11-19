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
