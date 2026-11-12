# 로컬 DB 생성

`schema.sql`은 ERD(dbdiagram)를 MySQL로 내보낸 SQL이며 ERD가 원본이다. 테이블 생성의 기준으로 사용하고, ERD를 고치면 다시 내보내 교체한다.

## 실행

Docker Desktop을 실행하고 저장소 루트에 개인용 `.env`를 준비한 뒤 실행한다.

```powershell
docker compose up -d --wait mysql redis
```

로컬 override가 SQL을 MySQL 초기화 디렉터리에 연결한다. 빈 데이터 볼륨의 최초 초기화 시 DB와 계정을 만든 뒤 SQL로 테이블·인덱스·외래키를 생성한다. 기본 `mysql-data` 볼륨을 사용하며 별도의 초안 DB는 만들지 않는다. 운영 Compose에는 SQL 자동 실행 설정이 포함되지 않는다.

MySQL은 `127.0.0.1:3306`, Redis는 `127.0.0.1:6379`로 연결한다. DB명·계정·비밀번호는 `.env`를 사용한다. IDE의 백엔드 실행 설정에도 `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` 환경 변수를 설정해야 한다. Compose의 `.env`는 로컬 Java 프로세스에 자동 전달되지 않는다.

## 엔티티와 스키마

최초 테이블은 ERD SQL로 생성한다. 로컬 프로파일은 팀 설정인 `ddl-auto: update`를 유지하므로 이후 엔티티에 따라 테이블이 자동 생성·변경될 수 있다. 운영 프로파일은 `ddl-auto: validate`로 매핑만 검사한다. 현재 엔티티가 없으므로 앱 실행 성공만으로 전체 스키마를 검증했다고 볼 수 없다. 인덱스·외래키 등의 검증도 별도로 필요하다. 기존 H2 테스트 프로파일은 MySQL 매핑 검증을 대체하지 않는다.

`schema.sql`에는 14개 테이블과 외래키·유일성 제약이 있고 초기 데이터는 없다. 초기 데이터(`categories` 5건, `concepts` 100건)는 `seed.sql`에 있으며 `docs/concepts.md`에서 생성한 파일이다. 로컬 override가 `schema.sql` 다음(`02-seed.sql`)에 실행한다. 배포 서버는 SQL이 자동 실행되지 않으므로 첫 배포 때 `schema.sql`, `seed.sql` 순서로 한 번 직접 실행한다. 단일 컬럼 PK 중 앱이 INSERT로 생성하는 테이블(`users`, `questions`, `matches` 등)은 `AUTO_INCREMENT`이므로 엔티티는 `GenerationType.IDENTITY`를 쓴다. 시드로 id를 고정해 넣는 `categories`, `rating_tiers`는 `AUTO_INCREMENT`가 아니다.

## 변경 또는 기존 DB 사용

SQL은 최초 초기화 때만 실행된다. 파일 변경이나 컨테이너 재시작만으로 기존 DB는 갱신되지 않는다. 기존 테이블이 있으면 전체 SQL을 재실행하지 않고 필요한 변경 SQL을 별도로 적용한다. 데이터 보존 여부를 확인하지 않고 볼륨을 삭제하지 않는다.

공통 설정은 SCRUM-84와 조율하며 SCRUM-38의 문창원 담당 엔티티는 `match_rounds`, `round_answers`이다. 이번 단계는 SQL 적용까지이며 엔티티 구현은 후속 작업이다.
