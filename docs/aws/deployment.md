# RE:ACT 운영 배포 가이드 (SCRUM-87, SCRUM-88)

> 이 문서는 RE:ACT의 현재 CI/CD 구성과 EC2 운영 배포 절차, 도메인 및 HTTPS 설정을 설명한다.
>
> 최초 Terraform 인프라 구축과 Hello World 공개 검증 과정은 [SCRUM-86 과거 기록](./deployment-scrum86-history.md)에 보관했다. 당시 사용한 `infra/preview/` 파일은 SCRUM-88에서 삭제했으며, 현재 운영에 사용하지 않는다.

## 1. 배포 구성

- 운영 주소: https://www.re-act.site
- AWS 리전: `ap-northeast-2` (서울)
- 서버: EC2 `react-ec2`, Amazon Linux 2023, `t3.small`
- 실행 서비스: Nginx Proxy Manager 2.17.1, Spring Boot Backend, Nginx 기반 Frontend, MySQL 8.4, Redis 7.4
- DNS: 가비아 A 레코드와 EC2 탄력적 IP 연결
- HTTPS: Nginx Proxy Manager 및 Let's Encrypt 인증서
- 배포 경로: GitHub Actions → GHCR 이미지 게시 → AWS Systems Manager(SSM) → EC2 Docker Compose
- 이미지 버전: Git 커밋의 전체 SHA(40자리)를 Backend·Frontend 이미지 태그로 사용
- EC2 운영 경로: `/opt/react`

### 요청 처리 구조

```text
사용자 → Nginx Proxy Manager (80/443)
              ↓
         Frontend Nginx (web:80)
              ├── /     → React 정적 파일
              ├── /api/ → Spring Boot (backend:8080)
              └── /ws   → Spring Boot (backend:8080)
```

Frontend, REST API, WebSocket은 동일한 도메인을 사용한다. 별도의 API 서브도메인을 사용하지 않으며, Frontend Nginx에서 요청 경로에 따라 Backend로 전달한다.

### 운영 포트

| 서비스 | EC2 호스트 포트 | 외부 접근 |
|---|---|---|
| NPM HTTP | `80` | 허용 |
| NPM HTTPS | `443` | 허용 |
| NPM 관리자 페이지 | `81` | 보안 그룹에서 차단 |
| Frontend | `127.0.0.1:18081` | EC2 내부에서만 접근 |
| Backend | `127.0.0.1:18080` | EC2 내부에서만 접근 |
| MySQL | 미공개 | Docker 내부 네트워크 |
| Redis | 미공개 | Docker 내부 네트워크 |

### 관련 파일

| 경로 | 역할 |
|---|---|
| `.github/workflows/ci.yml` | 빌드·테스트 및 Docker Compose Smoke Test |
| `.github/workflows/cd.yml` | CI 호출, GHCR 이미지 게시, SSM 배포 |
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
- 최초 배포 당시 외부 공인 IP의 HTTP 주소에서 RE:ACT 웹 화면 표시 확인
- **최초 배포 당시** 운영 MySQL `users` 테이블 데이터 수 0명 확인(로컬 개발 DB 데이터는 자동 이전되지 않음)
- Gemini·OpenAI 관련 운영 환경변수 입력 및 Backend 컨테이너 재생성 후 Health Check `UP` 확인

### SCRUM-87 도메인 및 HTTPS 적용 검증

- **HTTPS 적용:** 2026-11-24
- 가비아 DNS A 레코드와 EC2 탄력적 IP 연결
- Nginx Proxy Manager를 포함한 운영 컨테이너 5개 실행 확인
- Let's Encrypt 인증서 발급 및 HTTPS 접속 확인
- HTTP 요청의 HTTPS 리디렉션 확인(`301 Moved Permanently`)
- 공개 HTTPS 주소 응답 확인(`HTTP/2 200`)
- 운영 환경변수를 HTTPS 도메인 기준으로 변경
- Backend·Frontend 컨테이너 재생성 후 Health Check `UP` 확인
- `/api/v1/auth/csrf` 응답 `HTTP/2 200` 확인
- `HOST_SESSION` 쿠키의 `Secure`, `HttpOnly`, `SameSite=Lax` 속성 확인
- 브라우저에서 로그인, 강연방 및 청중 채팅 등 주요 기능 동작 확인
- NPM 관리자 포트(81) 인바운드 규칙 삭제 및 외부 TCP 연결 차단 확인

### 별도 검증이 필요한 내용

- 여러 사용자의 동시 강연방 입장·채팅·투표·WebSocket 기능
- Gemini 및 OpenAI 분석·보고서 내용의 정확성과 예외 상황
- 실제 사용량에서의 메모리·성능·안정성
- Let's Encrypt 인증서의 실제 자동 갱신
- **배포 실패를 의도적으로 발생시킨 롤백 시나리오**(현재는 복구 로직 구현 상태)

## 3. GitHub Actions 실행 조건

### 기본 동작

| 이벤트 | CI | CD |
|---|---|---|
| develop 대상 PR | 실행 | 실행하지 않음 |
| main 대상 PR | 실행 | 실행하지 않음 |
| 작업 브랜치 일반 Push | 실행하지 않음 | 실행하지 않음 |
| develop Push | 실행하지 않음 | 실행하지 않음 |
| main Push | CD에서 CI 재검증 | GHCR 게시 및 EC2 자동배포 |
| 수동 실행 (`deploy=false`) | CD에서 CI 실행 | 배포하지 않음 |
| main 수동 실행 (`deploy=true`) | CD에서 CI 재검증 | GHCR 게시 및 EC2 배포 |

- **SCRUM-88:** GitHub Actions CI/CD, GHCR 이미지 게시, AWS SSM 자동배포 구현 및 최초 배포 검증 완료.
- 작업 브랜치의 임시 배포 조건은 제거했으며, 운영 자동배포는 `main` Push를 기준으로 실행한다.
- `workflow_dispatch` UI의 사용 가능 여부는 기본 브랜치에 있는 워크플로 정의를 따른다.

`develop`에 PR을 Merge하더라도 EC2 자동배포가 바로 실행되지는 않는다. 운영 자동배포 트리거는 `main` Push이다.

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

운영 `.env`의 주요 변수 예시(실제 비밀값은 문서에 기록하지 않음):

```dotenv
MYSQL_ROOT_PASSWORD=<EC2에만 저장하는 비밀번호>
MYSQL_DATABASE=react
MYSQL_USER=react
MYSQL_PASSWORD=<EC2에만 저장하는 비밀번호>
PARTICIPANT_JOIN_SECRET=<Base64로 인코딩한 충분히 긴 비밀키>
ROOM_JOIN_BASE_URL=https://www.re-act.site
CORS_ALLOWED_ORIGINS=https://www.re-act.site
SESSION_COOKIE_SECURE=true
GEMINI_API_KEY=<Gemini API 키>
GEMINI_MODEL=<실제로 사용 가능한 모델명>
OPENAI_API_KEY=<OpenAI API 키>
REPORT_ALERT_WEBHOOK_URL=
```

- `ROOM_JOIN_BASE_URL`은 강연방 QR 코드 및 청중 참여 링크의 기준 주소로 사용한다.
- `CORS_ALLOWED_ORIGINS`는 운영 HTTPS 도메인과 일치하도록 설정한다.
- `SESSION_COOKIE_SECURE=true`로 설정하여 HTTPS에서만 세션 쿠키가 전송되도록 한다.
- `PARTICIPANT_JOIN_SECRET`는 **Base64 디코딩 결과 최소 32바이트**여야 한다. 형식이나 길이가 올바르지 않으면 Backend가 시작되지 않을 수 있다.
- 위의 꺾쇠괄호 값은 설명을 위한 자리표시자이며, 그대로 사용하면 안 된다.
- 실제 비밀번호·API 키·GHCR 토큰은 Git, 문서, Docker 이미지나 공개 로그에 포함하지 않는다.
- CI 테스트용 값이나 `.env.example`의 예시 비밀값을 운영에서 재사용하지 않는다.
- OpenAI 및 Gemini 외부 API 호출에는 별도의 사용량·요금·모델 지원 여부 확인이 필요하다.

### 보고서 AI 오류 알림

1. Discord의 **서버 설정 → 연동 → 웹훅**에서 웹훅을 만들고 개발자 알림을 받을 텍스트 채널을 선택한다. [Discord 웹훅 설정 안내](https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks)
2. 웹훅 URL을 복사해 운영 서버 `/opt/react/.env`의 `REPORT_ALERT_WEBHOOK_URL`에 설정한다. 주소에 비밀 토큰이 들어 있으므로 서버에만 보관한다.
3. 아래 ‘Backend 전용 변수 수정’ 절차로 새 값을 적용한다.

보고서의 AI 요청이나 결과 검증이 실패하면 보고서 상태와 알림을 MySQL에 함께 저장한다. 서버는 30초 간격으로 최대 20건을 묶어 보고서 ID, 최초 생성·재시도 구분, UTC 발생 시간 범위와 진단 코드를 Discord로 보낸다. 예를 들어 `OPENAI_HTTP_503`은 OpenAI 서버 오류, `OPENAI_HTTP_429_QUOTA`는 이용 한도, `INVALID_REPORT_EVIDENCE`는 보고서의 근거 검증 실패를 뜻한다.

Discord가 생성한 메시지 ID를 회신하면 해당 알림을 대기 목록에서 지운다. 전송이 실패하거나 확인 응답을 받지 못하면 5분 뒤부터 다시 시도한다. 전송 도중 프로세스가 중단되면 다른 작업자가 2분 뒤부터 이어받으므로 같은 알림이 다시 도착할 수 있다.

주소가 비어 있으면 알림은 DB에서 대기한다. 설정 전에 쌓인 새 알림도 주소를 연결하면 전송한다. 현재 대기 건수와 가장 오래된 오류 시각은 다음 쿼리로 확인한다.

```sql
SELECT COUNT(*) AS pending_alerts, MIN(created_at) AS oldest_failure_utc
FROM report_alert_outbox;
```

### 도메인 및 HTTPS 설정 (SCRUM-87)

가비아 DNS에서 다음과 같이 A 레코드를 설정한다.

| 타입 | 호스트 | 값 |
|---|---|---|
| A | `www` | EC2 탄력적 IP |

Frontend와 Backend는 동일한 도메인을 사용하므로 별도의 `api` 레코드는 필요하지 않다.

Nginx Proxy Manager는 운영 Docker Compose에 포함되어 있으며, 별도로 EC2에 설치하지 않는다.

최초 설정 또는 관리자 접근이 필요한 경우 다음 절차를 수행한다.

1. AWS EC2 보안 그룹에서 TCP 81번 포트를 관리자 공인 IP(`/32`)에만 임시 허용한다.
2. `main` Push에 따른 자동배포가 완료되면 NPM 컨테이너 실행 상태를 확인한다.
3. 브라우저에서 `http://<EC2 탄력적 IP>:81`로 접속한다.
4. 최초 접속 시 관리자 계정을 생성한다.
5. Proxy Hosts에서 다음과 같이 설정한다.

| 항목 | 설정값 |
|---|---|
| Domain Names | `www.re-act.site` |
| Scheme | `http` |
| Forward Hostname / IP | `web` |
| Forward Port | `80` |
| Access List | Publicly Accessible |
| Cache Assets | OFF |
| Block Common Exploits | ON |
| Websockets Support | ON |
| SSL Certificate | Let's Encrypt |
| Force SSL | ON |
| HTTP/2 Support | ON |
| HSTS Enabled | OFF |
| HSTS Sub-domains | OFF |
| Use DNS Challenge | OFF |

6. 인증서 발급 후 공개 HTTPS 주소에 접속하여 정상 동작을 확인한다.
7. 운영 `.env`의 도메인 및 세션 쿠키 설정을 변경하고 Backend·Frontend 컨테이너에 반영한다.
8. 서비스 검증을 완료한 후 AWS 보안 그룹의 TCP 81번 인바운드 규칙을 삭제한다.

NPM과 Frontend는 동일한 Docker 네트워크를 사용하므로 `web:80`으로 연결한다. EC2의 공인 IP나 호스트 Health Check 포트인 `18081`을 사용하지 않는다.

NPM에서 HTTPS를 처리하며, Frontend Nginx는 `X-Forwarded-Proto` 등의 전달 헤더를 Backend로 전달한다. Backend는 `SERVER_FORWARD_HEADERS_STRATEGY=framework` 설정을 사용한다.

NPM 설정 및 인증서는 다음 Docker Volume에 저장한다.

- `npm-data`: NPM 설정 및 관리자 계정
- `npm-letsencrypt`: Let's Encrypt 인증서 및 관련 데이터

해당 볼륨을 유지하면 NPM 컨테이너 재생성 시 기존 설정과 인증서를 재사용할 수 있다. 인증서 자동 갱신을 위해 관리자 포트(81)를 공개할 필요는 없다.

**주의:** 최초 NPM 적용 시 기존 80번 포트를 NPM으로 전환하므로 Proxy Host 설정이 완료되기 전까지 서비스 접속이 일시적으로 중단될 수 있다.

## 5. 실제 배포 흐름과 복구 범위

1. GitHub Actions에서 Backend·Frontend 테스트와 Docker Compose Smoke Test를 통과한다.
2. Git SHA를 태그로 하는 Backend·Frontend 이미지를 GHCR에 게시한다.
3. 배포 실행 직전에 원격 브랜치의 최신 SHA와 현재 실행 SHA가 일치하는지 확인한다.
4. AWS SSM으로 운영 Compose와 배포 스크립트를 `/opt/react/.staging`에 전달하고 SHA-256 체크섬을 검증한다.
5. EC2에 기존 운영 Compose·배포 스크립트를 백업한 후, 서버의 `.env`와 GHCR 읽기 인증으로 이미지를 내려받는다. NPM이 포함된 구성에서는 NPM 이미지도 내려받는다.
6. NPM 최초 도입 또는 제거 시 기존 서비스가 사용 중인 80번 포트를 반환한다.
7. `docker compose up -d --no-build --wait --wait-timeout 180`으로 서비스를 실행한다.
8. Spring Boot `/actuator/health`, Frontend HTTP 응답 및 NPM 관리자 페이지 응답을 확인한다.
9. 성공 시 운영 Compose·배포 스크립트를 확정하고 SHA를 `/opt/react/current-image-tag`에 기록한다.

현재 운영 구성의 Health Check 주소는 다음과 같다.

| 대상 | 주소 |
|---|---|
| Backend | `http://127.0.0.1:18080/actuator/health` |
| Frontend | `http://127.0.0.1:18081/` |
| NPM | `http://127.0.0.1:81/` |

배포 스크립트는 최대 30회 재시도하며, 실패한 검사 이후 3초간 대기한다.

내부 Health Check 성공이 공개 HTTPS 및 WebSocket의 정상 동작까지 보장하는 것은 아니므로 배포 완료 후 운영 도메인에서 별도로 확인한다.

배포 실패 시 이전 이미지 태그와 운영 Compose·배포 스크립트로 **복구를 시도**한다. NPM 도입 전 구성으로 복구할 경우 NPM이 사용 중인 80번 포트를 반환한다. 최초 배포에서는 이전 버전이 없으므로 자동 롤백할 수 없다.

**주의:** 이미지·설정 롤백이 데이터베이스의 이전 상태나 운영 `.env` 값을 복원하지는 않는다. Flyway 마이그레이션과 데이터 변경은 별도 백업·호환성 검토가 필요하다.

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

- NPM·Backend·Frontend 컨테이너 실행
- MySQL·Redis 컨테이너 `healthy`
- Health Check 결과 `{"status":"UP"}`
- 브라우저에서 HTTPS 웹 화면 표시(이 결과만으로 로그인·AI 기능 정상 동작을 보장하지 않음)
- 서버 메모리·디스크 여유 확인. 현재 `t3.small`은 약 2GiB RAM이고 Swap 없이 운영하므로 부하 테스트 시 메모리 확인이 필요하다.

### 공개 HTTP 및 HTTPS 확인

```bash
curl -I http://www.re-act.site
curl -I https://www.re-act.site
```

확인 기준:

- HTTP 요청: `301 Moved Permanently`
- 리디렉션 주소: `https://www.re-act.site/`
- HTTPS 요청: `HTTP/2 200`
- TLS 인증서 검증 오류 없음

### API 및 세션 쿠키 확인

```bash
curl -sS -o /dev/null -D - \
  https://www.re-act.site/api/v1/auth/csrf \
  | sed -E 's/(Set-Cookie: [^=]+=)[^;]*/\1[REDACTED]/I'
```

확인 기준:

- `HTTP/2 200`
- `HOST_SESSION` 쿠키 발급
- `Secure`, `HttpOnly`, `SameSite=Lax` 속성 확인

### NPM 관리자 포트 외부 접근 차단 확인

관리자 설정 완료 후 AWS 보안 그룹의 81번 인바운드 규칙을 삭제한다.

로컬 Windows PowerShell에서 다음을 실행한다.

```powershell
Test-NetConnection www.re-act.site -Port 81
```

`TcpTestSucceeded : False`로 외부 연결 차단 여부를 확인한다. EC2 내부에서는 배포 Health Check를 위해 81번 포트에 접근할 수 있어야 한다.

## 7. 운영 환경변수 변경

운영 `.env`는 EC2에만 보관하며 GitHub Actions로 덮어쓰지 않는다.

환경변수 변경 전 기존 파일을 백업한다.

```bash
sudo cp -p /opt/react/.env \
  "/opt/react/.env.backup-$(date +%Y%m%d-%H%M%S)"

sudo vi /opt/react/.env
```

백업 파일에도 운영 비밀값이 포함되므로 접근 권한을 제한하고 Git에 커밋하지 않는다.

### Backend 전용 변수 수정

`CORS_ALLOWED_ORIGINS`, `ROOM_JOIN_BASE_URL`, `SESSION_COOKIE_SECURE`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `OPENAI_API_KEY`처럼 **Backend 컨테이너에만 주입되는 변수**는 현재 이미지 태그를 유지한 채 컨테이너를 재생성하여 반영할 수 있다.

Backend 재생성 시 Docker 내부 IP가 변경될 수 있으므로 Frontend Nginx도 함께 재생성한다.

```bash
sudo bash -c '
set -euo pipefail
export IMAGE_TAG="$(cat /opt/react/current-image-tag)"
docker compose \
  --env-file /opt/react/.env \
  -f /opt/react/compose.prod.yaml \
  up -d --no-deps --force-recreate --no-build backend web
'

curl --retry 20 --retry-all-errors --retry-delay 3 -fsS \
  http://127.0.0.1:18080/actuator/health
```

HTTPS 관련 환경변수가 실제 컨테이너에 반영되었는지 확인한다.

```bash
sudo docker exec react-app-backend-1 printenv \
  | grep -E '^(ROOM_JOIN_BASE_URL|CORS_ALLOWED_ORIGINS|SESSION_COOKIE_SECURE)='
```

재생성 중에는 서비스 연결이 잠시 중단될 수 있다. 자동배포가 진행 중인 경우에는 위 명령어를 실행하지 않는다.

Health Check가 `UP`이어도 Gemini·OpenAI 키의 유효성과 실제 AI 호출 성공까지 검증된 것은 아니다. 재생성 이후 공개 HTTPS 및 API 응답도 함께 확인한다.

### MySQL 계정·비밀번호 변경 시 주의

`MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PASSWORD` 같은 **DB 계정 관련 변수는 위의 Backend 재생성 절차로 변경할 수 없다.**

MySQL은 이미 초기화된 데이터 볼륨의 계정 정보를 사용하므로, `.env` 값만 바꾸거나 컨테이너를 재생성해도 기존 계정·비밀번호는 자동 변경되지 않는다. 운영 DB 백업 후 별도의 계정 변경 절차를 수행해야 한다.

**운영 데이터 초기화를 위해 Docker 볼륨을 삭제하지 않는다.**

## 8. 보안 및 운영 주의 사항

- MySQL·Redis는 인터넷에 직접 공개하지 않는다. Backend Health Check 포트 `18080` 및 Frontend Health Check 포트 `18081`은 `127.0.0.1`에만 바인딩한다.
- 서버 접근은 AWS SSM을 사용하며 SSH 22번 포트는 개방하지 않는다.
- AWS 보안 그룹은 HTTP 80번과 HTTPS 443번 포트만 외부에 공개한다.
- NPM 관리자 포트(81)는 기본적으로 AWS 보안 그룹에서 차단한다. 관리가 필요한 경우에만 관리자 공인 IP에 임시 허용하고 설정 완료 후 삭제한다.
- NPM 관리자 페이지에 직접 접속하는 HTTP 통신은 암호화되지 않으므로 관리자 접근 및 비밀번호 관리에 주의한다.
- `SESSION_COOKIE_SECURE=true`를 유지하고 운영 서비스는 HTTPS 주소를 사용한다.
- HTTP 요청은 NPM에서 HTTPS로 리디렉션한다.
- Let's Encrypt 인증서 자동 갱신을 위해 도메인 DNS 및 인증서 검증에 필요한 외부 접근 설정을 유지한다.
- GitHub Actions 배포 실패 로그에 SSM의 `StandardErrorContent` 전체를 출력하지 않는다. SSM 로그 접근 권한도 제한한다.
- `docker compose down -v`는 운영 MySQL·Redis 데이터 및 NPM 설정·인증서 볼륨을 삭제할 수 있으므로 운영 EC2에서 실행하지 않는다.
- Terraform State, AWS Access Key, GHCR 토큰 및 운영 `.env`는 Git에 커밋하지 않는다.
- 배포 스크립트의 롤백은 이미지·설정 복구이며 DB 백업과 복원을 대체하지 않는다.

## 9. 관련 이슈 및 후속 작업

- **SCRUM-86:** Terraform을 이용한 AWS 인프라 구축 및 Hello World 검증. 당시 절차는 별도 역사 문서에 보관
- **SCRUM-88:** GitHub Actions CI/CD, GHCR 이미지 게시, AWS SSM 자동배포 구현 및 최초 배포 검증 완료
- **SCRUM-87:** 도메인 연결, Nginx Proxy Manager 기반 HTTPS/TLS 적용, HTTP → HTTPS 리디렉션, 운영 환경변수 변경 및 서비스 동작 검증 완료
- **통합 테스트:** 다중 사용자 환경에서 로그인·강연방·채팅·투표·Gemini 분석·OpenAI 보고서 생성 검증
- **운영 검증:** 실제 사용량에서의 성능·안정성, 배포 실패 시 롤백 및 인증서 자동 갱신 검증

운영 서비스 주소는 https://www.re-act.site 이며, 운영 자동배포 트리거는 `main` Push이다.
