> **SCRUM-86 과거 구축 기록**
>
> 이 문서는 Terraform 인프라 구축과 Hello World 배포를 최초 검증했을 당시의 기록이다.
> 현재 운영 배포 절차는 [RE:ACT 운영 배포 가이드](./deployment.md)를 참고한다.
> `infra/preview/` 파일은 현재 운영에 사용하지 않으므로 삭제했다.

**관련 이슈:** SCRUM-86 — AWS EC2 배포 환경 구성과 Hello World 공개 배포

### 작업 결과

- Terraform을 이용한 AWS 인프라 14개 리소스 생성
- AWS Systems Manager(SSM)를 통한 EC2 접속
- Docker 및 Docker Compose 실행 환경 구성
- Nginx를 이용한 Hello World 배포
- EC2 내부 HTTP 200 OK 응답 확인
- Docker Compose 전환 후 공인 IP를 통한 외부 접속 확인

**현재 완료 범위:** AWS 인프라 구축 및 Hello World 공개 배포 검증

SCRUM-86의 강연자·청중 화면 외부 접속 및 QR 참여 URL 검증은 아직 완료되지 않았다. 실제 RE:ACT 애플리케이션 배포, HTTPS 구성, GitHub Actions 자동 배포는 후속 작업으로 진행한다.

---

## 2. AWS 인프라 구성

### AWS 환경

| 항목 | 설정 |
|---|---|
| 리전 | ap-northeast-2 (서울) |
| EC2 | react-ec2 |
| 인스턴스 유형 | t3.small |
| 운영체제 | Amazon Linux 2023 |
| 스토리지 | 20GiB gp3 (암호화) |
| VPC | react-vpc |
| 공인 IP | 43.202.141.233 (Elastic IP) |
| 서버 접속 | AWS Systems Manager |
| 웹 서버 | Nginx (Docker) |
| 컨테이너 관리 | Docker Compose |

### 네트워크 및 리소스

Terraform으로 다음 AWS 리소스를 구성했다.

- VPC 및 Public Subnet
- Internet Gateway
- Route Table 및 라우팅 설정
- Security Group
- EC2 인스턴스 및 Elastic IP
- SSM용 IAM Role 및 Instance Profile

최초 `terraform apply` 실행 결과, 총 14개의 리소스가 정상 생성되었다.

### 보안 그룹

| 포트 | 용도 | 외부 접근 |
|---|---|---|
| 80/TCP | HTTP | 허용 |
| 443/TCP | HTTPS | 허용 |
| 22/TCP | SSH | 미허용 |
| 3306/TCP | MySQL | 미허용 |
| 6379/TCP | Redis | 미허용 |

현재 443번 포트는 보안 그룹에서 허용하고 있지만, HTTPS 인증서와 웹 서버 설정은 아직 적용하지 않았다.

---

## 3. Terraform 구성 및 관리

### 디렉터리 구조

```text
infra/
├── terraform/
│   ├── versions.tf
│   ├── providers.tf
│   ├── variables.tf
│   ├── main.tf
│   ├── outputs.tf
│   ├── user-data.sh
│   ├── terraform.tfvars.example
│   └── .terraform.lock.hcl
│
└── preview/
    ├── compose.yaml
    └── index.html
```

### Terraform 실행

**실행 환경:** 로컬 PC Git Bash

```bash
cd infra/terraform

export AWS_PROFILE=react

terraform init
terraform fmt -check
terraform validate
terraform plan
```

`react`는 현재 AWS 담당자의 로컬 AWS CLI 프로필 이름이다. 다른 환경에서는 해당 환경에 맞는 프로필을 설정해야 한다.

| 명령어 | 역할 |
|---|---|
| `init` | Provider 및 작업 환경 초기화 |
| `fmt -check` | 코드 형식 검사 |
| `validate` | Terraform 설정 검증 |
| `plan` | 적용할 AWS 변경 사항 확인 |
| `apply` | 검토한 변경 사항을 AWS에 적용 |

최초 인프라 생성 시 검증 명령 실행 후 `terraform apply`를 통해 AWS 리소스를 생성했다.

### 주의 사항

- 인프라 변경 전 반드시 `terraform plan` 결과를 확인한다.
- 기존 리소스가 생성된 상태이므로 불필요하게 `terraform apply`를 반복 실행하지 않는다.
- `terraform destroy`는 인프라 삭제를 초래할 수 있으므로 신중하게 사용한다.
- Terraform State와 실제 AWS 리소스의 상태를 유지하고 안전하게 관리한다.

---

## 4. EC2 접속 및 서버 환경

### SSM 접속

현재 EC2는 SSH 대신 AWS Systems Manager Session Manager를 사용한다.

1. AWS 콘솔에서 EC2 서비스로 이동한다.
2. `react-ec2` 인스턴스를 선택한다.
3. **연결(Connect)** 버튼을 선택한다.
4. **Session Manager** 탭에서 연결한다.

SSM Agent가 Online 상태이고 필요한 IAM 권한이 있어야 접속할 수 있다.

현재 SSH 22번 포트는 개방하지 않았으며, 별도의 SSH 키 페어 없이 SSM으로 접속할 수 있다.

### Docker 실행 환경

EC2 생성 시 `user-data.sh`를 사용하여 Docker와 Docker Compose를 설치하도록 구성했다.

최초 생성 과정에서는 패키지 충돌로 User Data 실행이 중단되어 기존 EC2에 Docker 및 Docker Compose를 수동 설치했다. 해당 오류와 해결 방법은 6절에 기록한다.

Docker 실행 상태 확인:

```bash
sudo docker --version
sudo systemctl is-active docker
sudo docker compose version
```

Docker 서비스가 `active` 상태이며 Docker Compose 명령이 정상 실행되는 것을 확인했다.

---

## 5. Hello World 배포

### 목적

실제 RE:ACT 애플리케이션을 배포하기 전에 다음 항목을 검증한다.

- EC2 정상 실행 여부
- Docker Compose 컨테이너 실행 여부
- Nginx HTTP 응답 여부
- 공인 IP를 통한 외부 인터넷 접속 여부

### Docker Compose 설정

**파일:** `infra/preview/compose.yaml`

```yaml
services:
  web:
    image: nginx:1.27-alpine
    restart: unless-stopped
    ports:
      - "80:80"
    volumes:
      - ./index.html:/usr/share/nginx/html/index.html:ro
```

### HTML 파일

**파일:** `infra/preview/index.html`

```html
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <title>RE:ACT</title>
</head>
<body>
  <h1>Hello World - RE:ACT</h1>
</body>
</html>
```

### 배포 위치

현재 EC2의 배포 디렉터리:

```text
/home/ec2-user/react/preview/
├── compose.yaml
└── index.html
```

`compose.yaml`과 `index.html`을 위 디렉터리에 배치한 후 실행한다.

**참고:** 현재 EC2의 파일은 수동으로 생성했다. Git 저장소에 파일을 추가하는 것만으로 EC2에 자동 전송되지는 않는다.

### Docker Compose 실행

**실행 환경:** EC2 SSM 터미널

설정 검증:

```bash
sudo docker compose \
  -f /home/ec2-user/react/preview/compose.yaml \
  config
```

컨테이너 실행:

```bash
sudo docker compose \
  -f /home/ec2-user/react/preview/compose.yaml \
  up -d
```

컨테이너 상태 확인:

```bash
sudo docker compose \
  -f /home/ec2-user/react/preview/compose.yaml \
  ps
```

정상 실행 시 `preview-web-1` 컨테이너가 `Up` 상태로 표시된다.

다른 컨테이너가 이미 80번 포트를 사용하고 있다면 포트 충돌이 발생할 수 있으므로 기존 컨테이너의 사용 여부를 확인해야 한다.

### 배포 검증

EC2 내부 HTTP 응답 확인:

```bash
curl -I http://localhost
```

실제 확인한 응답:

```text
HTTP/1.1 200 OK
Server: nginx/1.27.5
```

외부 브라우저 접속 주소:

http://43.202.141.233

출력 화면:

```text
Hello World - RE:ACT
```

### 검증 결과

| 검증 항목 | 결과 |
|---|---|
| Docker Compose 설정 검증 | 성공 |
| Nginx 컨테이너 실행 | 성공 |
| HTTP 80번 포트 연결 | 정상 |
| EC2 내부 HTTP 200 OK | 확인 완료 |
| 최초 Docker 단독 실행의 외부 접속 | 확인 완료 |
| Docker Compose 전환 후 외부 접속 | 확인 완료 |

최초 `docker run` 방식으로 외부 접속을 확인했다. 이후 기존 컨테이너를 제거하고 Docker Compose로 전환하여 컨테이너 실행, EC2 내부 HTTP 200 OK 응답, 공인 IP를 통한 Hello World 화면 표시까지 재확인했다.

---

## 6. 발생한 오류 및 해결

### 6.1. EC2 User Data 실행 실패

**증상**

EC2 생성 후 Docker 명령을 사용할 수 없었으며, `cloud-init` 실행 오류가 발생했다.

**원인**

Amazon Linux 2023에 기본 설치된 `curl-minimal`과 일반 `curl` 패키지가 충돌했다.

기존 `user-data.sh`:

```bash
dnf install -y docker curl
```

**해결**

`infra/terraform/user-data.sh`의 설치 명령을 수정했다.

```bash
dnf install -y docker
```

이미 생성된 EC2는 SSM을 통해 접속한 후 수동으로 복구했다.

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker
```

이후 Docker Compose를 별도로 설치하고 정상 실행을 확인했다.

**참고:** 수정된 User Data는 기존 EC2에서 자동으로 재실행되지 않는다. 현재 인스턴스는 수동 복구한 상태이며, 수정된 초기화 스크립트의 신규 인스턴스 재실행은 아직 검증하지 않았다.

---

## 7. Terraform State 및 보안 관리

### Terraform State

현재 Terraform State는 로컬에서 관리한다.

- `terraform.tfstate`는 Git에 포함하지 않는다.
- `.terraform/` 디렉터리는 Git 관리 대상에서 제외한다.
- 인프라 변경 적용 후 State를 별도로 백업한다.
- State 백업 파일은 안전한 저장 위치에 보관한다.

백업 명령어 예시:

**실행 환경:** 로컬 Git Bash의 `infra/terraform/`

```bash
umask 077
mkdir -p "$HOME/react-state-backup"

terraform state pull > \
  "$HOME/react-state-backup/react-$(date +%Y%m%d-%H%M%S).tfstate"
```

현재 State 백업을 완료했으며, 백업본은 로컬 외에도 별도의 암호화된 저장 위치에 보관하는 것이 좋다.

### AWS 인증 및 접근 권한

- AWS 콘솔은 교육기관 정책에 따라 지정된 AWS 담당자만 접근한다.
- 현재 EC2는 AWS 담당자가 SSM으로 관리하며, SSH 22번 포트는 개방하지 않았다.
- 교육기관 정책상 팀원의 Access Key 활용과 SSH 등의 서버 접근은 허용되지만, 팀원용 서버 접속 경로는 아직 구성하지 않았다.
- Access Key와 Secret Access Key는 Git 저장소에 포함하지 않고 안전하게 관리한다.

### 현재 적용된 보안 설정

- SSH 22번 포트 미개방
- SSM 기반 서버 접속
- EBS 암호화
- IMDSv2 필수 사용
- MySQL 및 Redis 포트 외부 미개방

현재는 HTTP 기반 테스트 환경이므로 실제 사용자 정보, 비밀번호 또는 인증 토큰을 전송하지 않는다.

---

## 8. 후속 작업

### 실제 RE:ACT 서비스 배포

- React 프론트엔드 Docker 이미지 빌드
- Spring Boot 백엔드 실행
- MySQL 및 Redis 연결
- 강연자·청중 화면의 외부 접속 검증
- QR 참여 URL 정상 동작 검증

### HTTPS 적용

- 서비스 도메인 연결
- TLS 인증서 적용
- HTTP → HTTPS 리디렉션
- HTTPS/WSS 기반 API 및 STT 기능 검증

### 자동 배포

- GitHub Actions에서 Docker 이미지 빌드
- GHCR에 이미지 게시
- AWS OIDC 및 IAM Role 연동
- SSM을 통한 EC2 배포 자동화

자동 배포는 별도 작업으로 진행한다.

---

## 9. 정리

Terraform으로 AWS EC2 인프라를 구축하고, Docker Compose와 Nginx를 이용한 Hello World 공개 배포까지 완료했다.

EC2 내부 HTTP 응답과 외부 브라우저 접속을 모두 확인했으며, 현재는 **AWS 인프라 및 초기 웹 배포 환경 검증이 완료된 상태**다.

실제 RE:ACT 애플리케이션의 강연자·청중 접속, QR 참여 기능, HTTPS 및 자동 배포는 아직 적용되지 않았다.