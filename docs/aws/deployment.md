# RE:ACT 운영 배포 가이드 (SCRUM-88)

> 이 문서는 RE:ACT의 현재 CI/CD 구성과 EC2 운영 배포 절차를 설명한다.
>
> 최초 Terraform 인프라 구축과 Hello World 공개 검증 과정은 [SCRUM-86 과거 기록](./deployment-scrum86-history.md)에 보관했다. 당시 사용한 `infra/preview/` 파일은 SCRUM-88에서 삭제했으며, 현재 운영에 사용하지 않는다.

## 1. 배포 구성

- AWS 리전: `ap-northeast-2` (서울)
- 서버: EC2 `react-ec2`, Amazon Linux 2023, `t3.small`
- 실행 서비스: Spring Boot Backend, Nginx 기반 Frontend, MySQL 8.4, Redis 7.4
- 배포 경로: GitHub Actions → GHCR 이미지 게시 → AWS Systems Manager(SSM) → EC2 Docker Compose
- 이미지 버전: Git 커밋의 전체 SHA(40자리)를 이미지 태그로 사용
- EC2 운영 경로: `/opt/react`

### 관련 파일

| 경로 | 역할 |
|---|---|
| `.github/workflows/ci.yml` | 빌드·테스트, GHCR 이미지 게시, SSM 배포 |
| `backend/Dockerfile` | Backend 이미지 빌드 |
| `frontend/Dockerfile` | Frontend 이미지 빌드 |
| `frontend/nginx.conf` | 정적 파일 서비스 및 `/api`, `/ws` 프록시 |
| `infra/deploy/compose.prod.yaml` | EC2 운영용 Docker Compose |
| `infra/deploy/deploy.sh` | 이미지 교체, 상태 확인 및 실패 시 롤백 시도 |
| `infra/terraform/` | AWS 네트워크·보안 그룹·EC2 등 인프라 정의 |
| `docker-compose.yml` | 로컬 개발 및 CI 검증용 구성(EC2 운영 배포에는 사용하지 않음) |

## 2. 배포 검증 기록

### GitHub Actions에서 검증한 내용

- **최초 자동배포 성공:** 2026-11-23, 커밋 `9689fce0e1bf4cd6a2c2759eab894daee0c278ee`
- [해당 GitHub Actions 실행 기록](https://github.com/prgrms-be-devcourse/NBE12-14-final-SSAK3/actions/runs/45122700448)
- Backend 테스트, Frontend 테스트·빌드 및 Docker Compose Smoke Test 통과
- Backend·Frontend GHCR 이미지 SHA 태그 게시 성공
- AWS SSM을 이용한 운영 Compose·배포 스크립트 전달 및 EC2 자동배포 성공

### EC2에서 별도로 확인한 내용

- MySQL, Redis, Backend, Frontend 컨테이너 4개 실행
- MySQL·Redis `healthy` 상태 및 Spring Boot `/actuator/health` 응답 `{"status":"UP"}` 확인
- 외부 공인 IP의 HTTP 주소에서 RE:ACT 웹 화면 표시 확인
- **최초 배포 당시** 운영 MySQL `users` 테이블 데이터 수 0명 확인(로컬 개발 DB 데이터는 자동 이전되지 않음)
- Gemini·OpenAI 관련 운영 환경변수 입력 및 Backend 컨테이너 재생성 후 Health Check `UP` 확인

### 별도 검증이 필요한 내용

- 최신 `develop` 변경 사항을 반영한 최종 배포 검증
- HTTPS 기반 로그인 및 세션 유지
- 여러 사용자의 강연방 입장·채팅·투표·WebSocket 기능
- Gemini 및 OpenAI 실제 API 호출과 분석·보고서 내용
- 실제 사용량에서의 메모리·성능·안정성
- **배포 실패를 의도적으로 발생시킨 롤백 시나리오**(현재는 복구 로직 구현 상태)

## 3. GitHub Actions 실행 조건

### 기본 동작

| 이벤트 | CI | GHCR 이미지 게시 | EC2 배포 |
|---|---|---|---|
| `develop` 또는 `main` 대상 PR | 실행 | 실행하지 않음 | 실행하지 않음 |
| `develop` Push | 실행 | 실행하지 않음 | 실행하지 않음 |
| `main` Push | 실행 | 실행 | 실행 |
| `main`에서 배포 옵션을 선택한 수동 실행 | 실행 | 실행 | 실행 |

### SCRUM-88 검증을 위한 일시적 예외

SCRUM-88 작업 브랜치 `chore/SCRUM-88-ghcr-image-publish`에서는 최초 배포 및 마지막 시험 배포를 위해 임시로 이미지 게시와 EC2 배포를 허용했다.

- 작업 브랜치 일반 Push: CI 및 GHCR 게시, EC2 배포 없음
- 커밋 메시지에 `[deploy-ec2]`가 포함된 지정된 작업자 Push: CI·이미지 게시 성공 후 EC2 배포
- 임시 수동 배포 경로도 작업 브랜치에 허용됨

**이 예외는 최신 `develop` 반영 최종 배포 검증이 끝나면 PR 제출 전에 `.github/workflows/ci.yml`에서 제거한다.** 이후에는 `main` Push를 운영 자동배포 트리거로 유지한다. `workflow_dispatch` UI의 사용 가능 여부는 기본 브랜치에 있는 워크플로 정의를 따른다.

## 4. 사전 준비 및 비밀값 관리

### GitHub 설정

- Repository Secrets: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- GHCR 쓰기 인증: GitHub Actions의 `GITHUB_TOKEN`
- AWS IAM 권한: 필요한 SSM 명령 전송 및 실행 상태 조회 권한

현재 배포는 **AWS Access Key 방식**을 사용한다. AWS OIDC 전환은 아직 적용하지 않았다.

### EC2 설정

- Docker·Docker Compose 및 SSM Agent 구동
- `/opt/react/.env`: 운영 환경변수 파일(root 소유, 권한 `600`)
- `/root/.docker/config.json`: root 계정의 GHCR 이미지 읽기 인증 파일(권한 `600`)

운영 `.env`의 주요 변수 예시(실제 값은 문서에 기록하지 않음):

```dotenv
MYSQL_ROOT_PASSWORD=<EC2에만 저장하는 비밀번호>
MYSQL_DATABASE=react
MYSQL_USER=react
MYSQL_PASSWORD=<EC2에만 저장하는 비밀번호>
PARTICIPANT_JOIN_SECRET=<Base64로 인코딩한 충분히 긴 비밀키>
ROOM_JOIN_BASE_URL=http://<운영 주소>
CORS_ALLOWED_ORIGINS=http://<운영 주소>
SESSION_COOKIE_SECURE=true
GEMINI_API_KEY=<Gemini API 키>
GEMINI_MODEL=<실제로 사용 가능한 모델명>
OPENAI_API_KEY=<OpenAI API 키>
```

- `PARTICIPANT_JOIN_SECRET`는 **Base64 디코딩 결과 최소 32바이트**여야 한다. 형식이나 길이가 올바르지 않으면 Backend가 시작되지 않을 수 있다.
- 위의 꺾쇠괄호 값은 설명을 위한 자리표시자이며, 그대로 사용하면 안 된다.
- 실제 비밀번호·API 키·GHCR 토큰은 Git, 문서, Docker 이미지나 공개 로그에 포함하지 않는다.
- CI 테스트용 값이나 `.env.example`의 예시 비밀값을 운영에서 재사용하지 않는다.
- OpenAI 및 Gemini 외부 API 호출에는 별도의 사용량·요금·모델 지원 여부 확인이 필요하다.

## 5. 실제 배포 흐름과 복구 범위

1. GitHub Actions에서 Backend·Frontend 테스트와 Docker Compose Smoke Test를 통과한다.
2. Git SHA를 태그로 하는 Backend·Frontend 이미지를 GHCR에 게시한다.
3. 배포 실행 직전에 원격 브랜치의 최신 SHA와 현재 실행 SHA가 일치하는지 확인한다.
4. AWS SSM으로 운영 Compose와 배포 스크립트를 `/opt/react/.staging`에 전달하고 SHA-256 체크섬을 검증한다.
5. EC2에 기존 운영 Compose·배포 스크립트를 백업한 후, 서버의 `.env`와 GHCR 읽기 인증으로 이미지를 내려받는다.
6. `docker compose up -d --no-build --wait --wait-timeout 180`으로 서비스를 실행한다.
7. Spring Boot `/actuator/health` 및 HTTP 웹 응답을 확인한다.
8. 성공 시 운영 Compose·배포 스크립트를 확정하고 SHA를 `/opt/react/current-image-tag`에 기록한다.

배포 실패 시 이전 이미지 태그와 운영 Compose·배포 스크립트로 **복구를 시도**한다. 최초 배포에서는 이전 버전이 없으므로 자동 롤백할 수 없다.

**주의:** 이미지·설정 롤백이 데이터베이스의 이전 상태를 복원하지는 않는다. Flyway 마이그레이션과 데이터 변경은 별도 백업·호환성 검토가 필요하다.

## 6. EC2 배포 상태 확인

AWS 콘솔의 EC2 **Session Manager**에서 접속한 후 다음을 실행한다.

```bash
sudo docker ps
sudo docker stats --no-stream
curl --retry 20 --retry-all-errors --retry-delay 3 -fsS \
  http://127.0.0.1:18080/actuator/health
free -h
df -h /
sudo cat /opt/react/current-image-tag
```

확인 기준:

- Backend·Frontend 컨테이너 실행
- MySQL·Redis 컨테이너 `healthy`
- Health Check 결과 `{"status":"UP"}`
- 브라우저에서 HTTP 웹 화면 표시(이 결과만으로 로그인·AI 기능 정상 동작을 보장하지 않음)
- 서버 메모리·디스크 여유 확인. 현재 `t3.small`은 약 2GiB RAM이고 Swap 없이 운영하므로 부하 테스트 시 메모리 확인이 필요하다.

## 7. 운영 환경변수 변경

운영 `.env`는 EC2에만 보관하며 GitHub Actions로 덮어쓰지 않는다.

```bash
sudo nano /opt/react/.env
```

### Backend 전용 변수 수정

`CORS_ALLOWED_ORIGINS`, `ROOM_JOIN_BASE_URL`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `OPENAI_API_KEY`처럼 **Backend 컨테이너에만 주입되는 변수**는 현재 이미지 태그를 유지한 채 Backend만 재생성해 반영할 수 있다.

```bash
sudo bash -c '
set -euo pipefail
export IMAGE_TAG="$(cat /opt/react/current-image-tag)"
docker compose \
  --env-file /opt/react/.env \
  -f /opt/react/compose.prod.yaml \
  up -d --no-deps --force-recreate --no-build backend
'

curl --retry 20 --retry-all-errors --retry-delay 3 -fsS \
  http://127.0.0.1:18080/actuator/health
```

재생성 중에는 API 연결이 잠시 중단될 수 있다. Health Check가 `UP`이어도 Gemini·OpenAI 키의 유효성과 실제 AI 호출 성공까지 검증된 것은 아니다.

### MySQL 계정·비밀번호 변경 시 주의

`MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PASSWORD` 같은 **DB 계정 관련 변수는 위의 Backend 재생성 절차로 변경할 수 없다.**

MySQL은 이미 초기화된 데이터 볼륨의 계정 정보를 사용하므로, `.env` 값만 바꾸거나 컨테이너를 재생성해도 기존 계정·비밀번호는 자동 변경되지 않는다. 운영 DB 백업 후 별도의 계정 변경 절차를 수행해야 한다.

**운영 데이터 초기화를 위해 Docker 볼륨을 삭제하지 않는다.**

## 8. 보안 및 운영 주의 사항

- MySQL·Redis는 인터넷에 직접 공개하지 않는다. Backend Health Check 포트 `18080`은 `127.0.0.1`에만 바인딩한다.
- 서버 접근은 AWS SSM을 사용하며 SSH 22번 포트는 개방하지 않는다.
- `SESSION_COOKIE_SECURE=true`를 유지한다. 현재 공개 주소는 HTTP이므로 로그인 세션 쿠키가 브라우저에서 정상 유지되지 않을 수 있다.
- **SCRUM-87:** 도메인·HTTPS/TLS 적용, HTTP → HTTPS 리디렉션 및 로그인·WebSocket 검증은 추후 진행한다. 공개 HTTP 주소에서 실제 계정·비밀번호를 입력해 테스트하지 않는다.
- GitHub Actions 배포 실패 로그에 SSM의 `StandardErrorContent` 전체를 출력하지 않는다. SSM 로그 접근 권한도 제한한다.
- `docker compose down -v`는 운영 MySQL·Redis 볼륨 데이터를 삭제할 수 있으므로 운영 EC2에서 실행하지 않는다.
- Terraform State, AWS Access Key, GHCR 토큰 및 운영 `.env`는 Git에 커밋하지 않는다.
- 배포 스크립트의 롤백은 이미지·설정 복구이며 DB 백업과 복원을 대체하지 않는다.

## 9. 관련 이슈 및 후속 작업

- **SCRUM-86:** Terraform을 이용한 AWS 인프라 구축 및 Hello World 검증. 당시 절차는 별도 역사 문서에 보관
- **SCRUM-88:** GitHub Actions CI/CD, GHCR 이미지 게시, AWS SSM 자동배포 구현 및 최초 배포 검증 완료. 최신 `develop` 반영 최종 배포 검증과 PR 정리 진행
- **SCRUM-87:** 도메인·HTTPS 적용은 추후 별도 진행
- **통합 테스트:** 팀원들과 로그인·강연방·채팅·투표·Gemini 분석·OpenAI 보고서 생성 검증(HTTPS 적용 전 공개 HTTP에서 실제 인증정보 전송 금지)

`develop`에 SCRUM-88 PR을 Merge하더라도 EC2 자동배포가 바로 실행되지는 않는다. 운영 자동배포 트리거는 `main` Push이다.
