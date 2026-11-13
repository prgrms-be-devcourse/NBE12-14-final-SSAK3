# MySQL 테스트 실행

JDK 25와 실행 중인 MySQL 8.4가 필요하다. H2는 사용하지 않는다.

테스트는 개발 DB `csmatch` 대신 `csmatch_test`(인증·컨텍스트 테스트), `csmatch_ws_test`(WebSocket 통합 테스트)를 사용한다. 테스트 전용 계정 `csmatch_test`에는 이 두 DB의 권한만 부여한다. 통합 테스트는 `create-drop`으로 테이블을 생성·삭제하므로 테스트 DB에 보관할 데이터를 넣지 않는다. `test` 프로파일은 Flyway를 끄므로 테스트 DB에는 마이그레이션 이력이 남지 않는다.

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

환경 변수로도 같은 값을 전달할 수 있다. 테스트 설정은 backend 작업 디렉터리를 기준으로 `../.env.test`를 읽는다. DB 이름과 계정은 테스트 전용 값으로 고정한다.

## 실행

```powershell
cd backend
./gradlew.bat build --no-daemon
```

실제 HTTP 회원가입·로그인과 WebSocket 연결을 통해 발급된 JWT의 인증 및 PING/PONG을 확인한다. 서명 변조·만료·탈퇴·삭제 회원의 토큰 거절도 검증한다. 탈퇴·삭제 상태는 테스트 DB에 직접 설정한다. 단위 테스트의 Mockito 모킹은 시간·전송 오류 등 개별 동작 검증에만 사용한다.

CI는 임시 MySQL 8.4 서비스와 동일한 초기화 SQL을 사용한다. 워크플로의 비밀번호는 CI 임시 DB 전용이며 개발·배포 자격 증명이 아니다. 여러 테스트 실행을 같은 DB에서 동시에 돌리지 않는다.

테스트 테이블은 JPA 엔티티에서 생성하므로 이 테스트만으로 ERD SQL과 엔티티가 일치하는지까지 검증하지는 않는다.
