# RE:ACT

첫 진입 `/`은 로그인 상태에 따라 `/login` 또는 `/host/rooms/new`로 이동합니다. STT 확인용 화면은 `/dev/stt`입니다.

강연자 인증 화면: `/login`, `/signup`에서 실제 인증 API와 연결합니다. [인증 화면 결정 사항·검증](docs/host-flow-screen.md)

강연방 생성·상세 화면: 프론트 실행 후 `/host/rooms/new`로 접속합니다. 로그인과 백엔드 실행이 필요합니다. [화면 동작·연동 범위](docs/room-screen.md)

AI 청중 질문·의견 요약: 개발 환경의 강연방 상세에서 `AI 요약 확인`을 누르거나 `/dev/rooms/{roomId}/ai`로 접속합니다. 실제 저장된 분석·근거 원문을 조회하며 로그인과 방 소유권이 필요합니다. [SCRUM-154·155 화면 정책·확인 방법](docs/ai-audience-screen.md)

청중의 질문과 반응이 강연에 반영되도록 돕는 실시간 소통 플랫폼. (백엔드 12기 14회차 · 3팀)

강연자가 강연방을 열면 청중이 QR 코드로 입장해 채팅·이해도·투표로 반응하고, 강연자는 대시보드에서 실시간으로 확인한다. 강연 종료 후에는 STT 발언과 청중 반응을 시간대별로 연결한 AI 회고 보고서를 제공한다.

Jira: https://ssakthree.atlassian.net/jira/software/projects/SCRUM/boards/1

## 구조

```
backend/src/main/java/com/react/
  global/        config, exception, response, security, websocket, monitoring, util
  <도메인>/       controller, service, repository, entity, domain, dto
                 도메인: auth, room, chat, poll, stt, ai, dash, report
backend/src/test/java/com/react/
  <도메인>/       기능별 단위·통합 테스트
  integration/   주요 API 흐름 및 동시성 통합 테스트

frontend/        React + Vite
docs/            기획, 기능 정의, API 명세, ERD, 테스트 및 배포 문서
infra/
  terraform/     AWS 인프라 구성
  deploy/        운영 Docker Compose 및 배포 스크립트
  monitoring/    로컬 Prometheus·Grafana·MySQL Exporter 구성
  k6/            부하 테스트 스크립트
.github/workflows/  CI/CD 워크플로
docker-compose.yml  mysql, redis, backend, web(Nginx)
```

## 시작하기

```bash
cp .env.example .env            # 값 채우기
docker compose up -d mysql redis
```

Backend 실행:

```bash
cd backend
./gradlew bootRun
```

또는 IDE에서 ReActApplication을 실행합니다(JDK 25 필요).

별도의 터미널에서 저장소 루트 기준으로 Frontend를 실행합니다.

```bash
cd frontend
npm install
npm run dev
```

Frontend 개발 주소: http://localhost:5173

전체 실행(로컬): `docker compose up -d --build` (http://localhost)

강연방을 종료하면 생성 상태가 표시됩니다. 완료 후 **회고 보고서 보기**를 누르면 해당 강연의 요약과 기록을 볼 수 있습니다. 가상 데이터로 화면을 살펴보려면 `/preview/reports`를 여세요. [보고서 화면 구성·실행 방법](docs/experiments/report-ui.md)

이전에 종료한 방은 강연자 메뉴의 **내 강연 기록**에서 다시 열 수 있습니다. 최근 강연을 10개씩 볼 수 있고, 완료된 회고 보고서도 같은 목록에서 열 수 있습니다.

## 서비스 운영 제한

| 항목 | 기본값 |
| --- | --- |
| 최대 동시 LIVE 강연방 | 6개 |
| 강연자당 동시 LIVE 강연방 | 1개 |
| 강연방당 최대 등록 청중 | 50명 |
| 전체 LIVE 강연방 최대 등록 청중 | 300명(6개 × 50명, 파생값) |
| 최대 강연 시간 | 180분 |

강연 시작 및 신규 청중 입장 시 서버에서 운영 제한을 검증합니다. 최대 강연 시간에 도달한 강연방은 서버에서 자동 종료합니다.

청중 인원은 현재 접속 수가 아닌 등록 참여자 수를 기준으로 합니다. 기존 청중의 유효한 재입장은 정원에 도달한 경우에도 허용합니다.

위 제한값은 기본 운영 설정입니다. 최대 운영 규모의 실제 동시 사용자 처리 성능은 별도의 부하 테스트로 검증합니다.

전체 300명에 대한 별도 절대 상한 검사는 없습니다. 자세한 제한 정책과 성능 목표는 [기능 정의서](docs/feature-spec.md) §15를 참고하세요.

## 테스트 및 모니터링

Backend 테스트:

```bash
cd backend
./gradlew test
```

테스트 전용 MySQL DB인 `react_test`를 준비해야 합니다. 상세 준비 및 실행 방법은 [테스트 안내](docs/testing.md)를 참고하세요.

Frontend 테스트:

```bash
cd frontend
npm test
```

로컬 모니터링과 k6 부하 테스트:

- [Prometheus·Grafana 구성](infra/monitoring/)
- [k6 부하 테스트 스크립트](infra/k6/)
- [여러 강연 동시 종료 및 보고서 생성 부하 테스트](infra/k6/report-end.md)

## 운영 배포

운영 서비스: https://www.re-act.site

배포(EC2): `main` 브랜치에 Push하면 GitHub Actions가 CI 검증을 수행하고 GHCR에 Docker 이미지를 게시한 뒤 AWS SSM을 통해 EC2에 자동 배포합니다. 운영 환경에서는 소스 코드를 직접 빌드하지 않습니다.

`develop` 및 `main` 대상 Pull Request에서는 Backend 빌드·테스트, Frontend 테스트·빌드 및 Docker Compose Smoke Test를 수행합니다.

운영 인프라는 Terraform으로 관리하며, Nginx Proxy Manager와 Let's Encrypt를 통해 HTTPS를 제공합니다. Frontend, REST API, WebSocket은 동일한 도메인을 사용합니다.

자세한 배포 절차, 도메인 및 HTTPS 설정, 운영 명령어는 [AWS 운영 배포 가이드](docs/aws/deployment.md)를 참고하세요.

## 프로젝트 문서

| 문서 | 내용 |
| --- | --- |
| [기획서](docs/planning.md) | 서비스 개요, 아키텍처, 기술 스택 및 배포 |
| [기능 정의서](docs/feature-spec.md) | 기능별 요구사항, 운영 제한 및 성능 목표 |
| [API 명세서](docs/api-spec.md) | REST·STOMP API 및 도메인별 명세 |
| [ERD](docs/db/erd.dbml) | DB 테이블 구조 및 관계 |
| [테스트 안내](docs/testing.md) | MySQL 테스트 환경 및 통합 테스트 |
| [AWS 운영 배포 가이드](docs/aws/deployment.md) | Terraform, CI/CD, EC2 운영 및 복구 |
| [협업 컨벤션](docs/conventions.md) | 브랜치·커밋·PR·Jira 규칙 |

설정 파일: `application.yml`(공통), `application-local.yml`(로컬, 기본 프로파일), `application-prod.yml`(배포, 환경 변수로만 주입), `application-test.yml`(테스트 전용)

요구 사항: JDK 25, Node 22, Docker. Gradle 은 Wrapper(`backend/gradlew`)를 사용하므로 따로 설치하지 않습니다.

> 스키마는 서버 기동 시 Flyway(`backend/src/main/resources/db/migration`)가 자동 적용합니다.
>
> 테스트 프로파일에서는 Flyway 자동 실행이 비활성화되며 필요한 테스트에서 마이그레이션을 직접 실행합니다.
>
> 이전 프로젝트의 DB 볼륨 때문에 로컬 개발 환경에서 초기화가 필요한 경우에만 `docker compose down -v`를 사용할 수 있습니다.
>
> **주의:** `docker compose down -v`는 Docker Compose가 관리하는 볼륨 데이터를 삭제합니다. 운영 EC2에서는 실행하지 마세요. 운영 MySQL·Redis 데이터와 Nginx Proxy Manager 설정 및 SSL 인증서가 삭제될 수 있습니다.
