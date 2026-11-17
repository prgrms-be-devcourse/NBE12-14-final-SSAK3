# MySQL 테스트 실행

JDK 25와 실행 중인 MySQL 8.4가 필요하다. H2는 사용하지 않는다.

테스트는 개발 DB `csmatch` 대신 `csmatch_test`를 사용한다. 테스트 전용 계정 `csmatch_test`에는 이 DB의 권한만 부여한다. 통합 테스트는 `create-drop`으로 테이블을 생성·삭제하므로 테스트 DB에 보관할 데이터를 넣지 않는다. `test` 프로파일은 Flyway를 끄므로 테스트 DB에는 마이그레이션 이력이 남지 않는다.

## 최초 준비

1. 저장소 루트에서 `docker compose up -d mysql`로 로컬 MySQL을 실행한다.
2. MySQL 관리자 연결에서 전용 계정을 생성한다. 아래 비밀번호 자리에는 새 테스트용 비밀번호를 지정한다.

   ```sql
   CREATE USER 'csmatch_test'@'%' IDENTIFIED BY '<테스트 전용 비밀번호>';
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
