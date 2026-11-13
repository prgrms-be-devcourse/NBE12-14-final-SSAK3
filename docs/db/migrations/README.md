# 연습 모드 스키마 변경 (과거 수동 적용 기록)

> 이 문서는 Flyway 도입(SCRUM-112) 전의 수동 적용 기록이다. 새 스키마 변경은 `backend/src/main/resources/db/migration`의 `V` 파일로 추가한다. 새 DB는 Flyway가 최신 스키마로 만든다.

`practice_*` 변경 전 스키마를 가진 기존 DB를 Flyway 도입 전에 갱신할 때만 쓰는 SQL이다. Docker의 초기화 SQL은 빈 DB 볼륨을 처음 초기화할 때만 실행되었다.

기존 DB는 새 버전 서버를 시작하기 전에 서버를 중지하고 백업한 뒤, `20261112-practice-history.sql`을 해당 DB에 한 번 적용한다. 이후 서버를 실행한다. 팀원별 로컬 DB와 배포 DB에 각각 적용해야 하며 Git pull이나 컨테이너 재시작만으로는 적용되지 않는다.

MySQL 클라이언트에서 대상 DB를 선택한 후 실행한다:

```sql
SOURCE /absolute/path/to/docs/db/migrations/20261112-practice-history.sql;
```

- 이 파일은 변경 전 스키마용 단일 적용 SQL이다. 새 스키마로 만든 DB에는 실행하지 않는다. Hibernate `update`로 일부 변경을 먼저 적용한 DB에도 그대로 재실행하지 않는다.
- Flyway 도입 후 로컬·배포 모두 `ddl-auto: validate`라서 Hibernate가 스키마를 변경하지 않는다.
- 기존 연습 요약은 회차 0으로 보존한다. 종료 시각이 있는 기록은 완료, 없는 기록은 중도 종료로 전환한다. 과거 개별 답안은 원본 데이터가 없어 복원되지 않는다.
- 새로운 연습은 회차 1부터 시작한다. 기존 회원·문제·경기·복습 기록은 유지한다.
- MySQL DDL은 자동 커밋된다. 중간 실패 시 현재 스키마를 확인하고 남은 변경을 적용해야 하며, 이 SQL 전체를 무조건 재실행하지 않는다.

테스트용 DB 준비는 `docs/db/init-test-databases.sql`을 사용한다. 연습 통합 테스트는 개발 DB와 별개인 `csmatch_practice_test`에서 테이블을 생성·삭제한다.
