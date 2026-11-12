-- csmatch_test 계정을 먼저 생성한다. 개발 DB 접근 권한은 부여하지 않는다.
CREATE DATABASE IF NOT EXISTS csmatch_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS csmatch_ws_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS csmatch_practice_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON csmatch_test.* TO 'csmatch_test'@'%';
GRANT ALL PRIVILEGES ON csmatch_ws_test.* TO 'csmatch_test'@'%';
GRANT ALL PRIVILEGES ON csmatch_practice_test.* TO 'csmatch_test'@'%';
