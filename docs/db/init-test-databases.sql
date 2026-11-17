-- react_test 계정을 먼저 생성한다. 개발 DB 접근 권한은 부여하지 않는다.
CREATE DATABASE IF NOT EXISTS react_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON react_test.* TO 'react_test'@'%';
