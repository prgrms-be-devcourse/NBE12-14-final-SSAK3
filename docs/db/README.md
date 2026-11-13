# DB 스키마 관리 (Flyway)

테이블과 초기 데이터는 Flyway가 백엔드 기동 시 자동으로 적용한다. 마이그레이션 파일은 `backend/src/main/resources/db/migration`에 있다.

| 파일 | 내용 |
|---|---|
| `V1__init_schema.sql` | 14개 테이블, 외래키·유일성 제약, 인덱스 (ERD 내보내기 SQL 기준) |
| `V2__seed_categories_concepts.sql` | `categories` 5건, `concepts` 100건 (`docs/concepts.md`에서 생성) |

`docs/db/schema.sql`은 ERD(dbdiagram)를 내보낸 참고본이다. 실제 적용 기준은 Flyway 마이그레이션이며, ERD를 고치면 새 `V` 파일을 추가하고 `schema.sql`도 함께 갱신한다.

## 로컬 실행

Docker Desktop을 실행하고 저장소 루트에 개인용 `.env`를 준비한 뒤 실행한다.

```powershell
docker compose up -d --wait mysql redis
cd backend
./gradlew bootRun
```

MySQL은 `127.0.0.1:3306`, Redis는 `127.0.0.1:6379`로 연결한다. DB명·계정·비밀번호는 `.env`를 사용한다. IDE의 백엔드 실행 설정에도 `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` 환경 변수를 설정해야 한다. Compose의 `.env`는 로컬 Java 프로세스에 자동 전달되지 않는다.

MySQL 컨테이너는 빈 DB만 만든다. 테이블과 시드는 백엔드를 처음 띄울 때 Flyway가 만든다.

## 스키마를 바꾸는 방법

1. `V<다음 번호>__<설명>.sql` 파일을 `db/migration`에 추가한다. (예: `V3__add_xxx.sql`)
2. 이미 적용된 `V` 파일은 수정하지 않는다. 내용이 바뀌면 Flyway 검증(checksum)이 실패한다.
3. 엔티티를 바꿔도 `ddl-auto`는 `validate`라서 테이블이 자동으로 바뀌지 않는다. 반드시 마이그레이션을 함께 넣는다.
4. MySQL DDL은 자동 커밋되므로 한 파일에 DDL을 많이 넣지 않는다. 중간에 실패하면 `flyway_schema_history`와 실제 스키마가 어긋날 수 있다.
5. 시드(개념)를 고칠 때는 `docs/concepts.md`를 고친 뒤 새 `V` 파일로 반영한다.

## 기존 DB 처리 (baseline)

이미 테이블이 있고 `flyway_schema_history`가 없는 DB(팀원 로컬, 배포 DB)는 `baseline-on-migrate`로 V2까지 적용된 것으로 기록하고 V3부터 실행한다. 따라서 **기존 DB는 V1·V2 시점의 스키마와 시드를 이미 갖고 있어야 한다.**

- 연습 모드 변경(`docs/db/migrations/20261112-practice-history.sql`) 전의 옛 스키마 DB는 baseline 후 Hibernate `validate`에서 `missing table [practice_answers]`로 실패한다. 그 SQL을 먼저 적용하거나, 로컬이라면 아래처럼 볼륨을 지우고 다시 만든다.
- 배포 DB는 첫 배포 전에 백업한다. `flyway_schema_history`가 생성되며 baseline 버전 2가 기록된다.
- 로컬 DB 초기화: `docker compose down -v` 후 `docker compose up -d --wait mysql redis`. 볼륨의 데이터가 모두 삭제되므로 필요한 데이터는 먼저 백업한다.

## 엔티티와 스키마

앱은 `ddl-auto: validate`로 엔티티와 테이블 매핑만 검사한다. 단일 컬럼 PK 중 앱이 INSERT로 생성하는 테이블(`users`, `questions`, `matches` 등)은 `AUTO_INCREMENT`이므로 엔티티는 `GenerationType.IDENTITY`를 쓴다. 시드로 id를 고정해 넣는 `categories`, `rating_tiers`는 `AUTO_INCREMENT`가 아니다.

## 테스트

통합 테스트(`test` 프로파일)는 Flyway를 끄고 `create-drop`으로 별도 테스트 DB에 테이블을 만든다. 준비 방법은 `docs/testing.md`를 따른다.
