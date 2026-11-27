# 협업 컨벤션

## 브랜치

- `main`(배포용) / `develop`(통합): 직접 push 금지. 항상 PR 로 머지한다.
- 작업 브랜치는 `develop` 에서 생성 → PR 머지 → 삭제
- 이름: `타입/SCRUM-번호-설명` (예: `feat/SCRUM-32-login`)
  - 타입: `feat` `fix` `refactor` `test` `chore` `etc`
- 흐름: 작업 브랜치 → `develop` → `main`

## 커밋

- 형식: `타입: 내용 (SCRUM-번호)` (예: `feat: 로그인 API 구현 (SCRUM-32)`)
- 타입: `feat` `fix` `refactor` `test` `chore` `etc`
- 한 커밋에 한 작업만 담는다.

## PR

- 제목: `[feat]` `[fix]` `[refactor]` `[test]` `[chore]` (2개 이상이면 `[feat] a, [fix] b`)
- 본문 템플릿(`.github/PULL_REQUEST_TEMPLATE.md`): 작업 내용 / 관련 이슈 / 테스트 방법 / 리뷰어에게
- 머지 후 작업 브랜치를 삭제한다.
- 최소 **1명 승인** 후 머지한다(브랜치 보호 규칙으로 강제).
- CI 통과: `develop` 및 `main` 대상 PR 에서 `.github/workflows/ci.yml` 이 백엔드 빌드·테스트, 프론트 테스트·빌드 및 Docker Compose Smoke Test 를 실행한다. 통과한 뒤 머지한다.
- 작업 브랜치와 `develop`의 일반 push 에서는 CI/CD 를 실행하지 않는다.
- `main` push 시 `.github/workflows/cd.yml` 이 CI 를 재호출하여 검증하고, 성공하면 GHCR 이미지 게시 및 EC2 자동배포를 수행한다.
- CD 수동 실행 시 `deploy=false` 는 CI 검증만 수행한다. 운영 배포는 `main` 브랜치에서 `deploy=true` 로 실행한 경우에만 수행한다.
- 자세한 실행 조건과 배포 절차는 [AWS 운영 배포 가이드](aws/deployment.md)를 따른다.

## Jira

- 모든 작업은 Jira 이슈로 등록한다. 브랜치·커밋·PR 에 이슈 번호(`SCRUM-32`)를 반드시 포함한다.
- 에픽: 큰 기능 묶음(진행률 확인용). 작업: 하나가 1~2일 안에 끝나는 크기. 버그는 작업으로 만들고 `bug` 라벨을 붙인다.
- 작업 제목: `[BE]` `[FE]` `[INFRA]` `[DOCS]` + 내용. 라벨은 `be` `fe` `infra`.
- 설명: 작업 내용 / 완료 조건(체크박스). 템플릿은 SCRUM-32 `[템플릿] 작업` 복제 후 `template` 라벨 제거.
- 상태(본인이 직접 변경): 시작 = 진행 중 → PR 올림 = 검토 중 → 머지 = 완료.
- 작업을 요청할 때는 팀원을 태그한다.

## 코드

- 아직 합의된 코드 컨벤션이 없다. 새 코드는 주변 코드의 이름·구조·주석 스타일을 따른다.
- 백엔드 패키지: `com.react.<도메인>.{controller,service,repository,entity,dto}`, 공통은 `com.react.global.*`
- 테스트는 `backend/src/test/java/com/react/<도메인>/` 에 같은 구조로 둔다.
- 시크릿·`.env` 는 커밋하지 않는다. 설정은 환경 변수로 주입한다.