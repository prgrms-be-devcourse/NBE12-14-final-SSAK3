# RE:ACT Monitoring

## 개요

SCRUM-91: Prometheus 및 Grafana 기반 애플리케이션 모니터링 환경 구축.

Spring Boot Actuator와 Micrometer를 사용하여 서버, DB, WebSocket, 채팅 및 AI 관련 메트릭을 수집한다.

## 실행 방법

Spring Boot 애플리케이션을 실행한 후 프로젝트 루트에서 다음 명령어를 실행한다.

```bash
docker compose -f infra/monitoring/compose.monitoring.yaml up -d
```

## 접속 정보

| 서비스 | 주소 |
|---|---|
| Spring Boot Actuator | http://localhost:8080/actuator/prometheus |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

Grafana 초기 로그인 정보는 admin / admin이며 최초 로그인 시 비밀번호 변경을 권장한다.

데이터소스와 대시보드는 실행 시 자동으로 등록된다.

## 모니터링 지표

### Overview

- CPU 사용률
- JVM 메모리 사용량
- HTTP 요청 처리량
- HTTP 평균 응답 시간 및 p95, p99
- HTTP 오류율
- DB 커넥션 풀 상태

### Infrastructure

- DB 커넥션 대기 수
- JVM 스레드 수
- GC 평균 정지 시간
- Redis 평균 명령 처리 시간

### RE:ACT Service

- WebSocket 활성 스레드 및 대기 작업 수
- AI 스케줄러 실행 횟수 및 평균 실행 시간
- 활성 STOMP 세션 수
- 채팅 메시지 저장 처리량
- 채팅 방송 호출 실패 횟수
- Gemini LLM 호출 성공 및 실패 횟수
- Gemini LLM 토큰 사용량
- Gemini LLM 추정 비용

## 종료 방법

```bash
docker compose -f infra/monitoring/compose.monitoring.yaml down
```

Prometheus 및 Grafana 데이터는 Docker named volume에 저장된다.

## 참고 사항

- Prometheus는 15초 간격으로 메트릭을 수집한다.
- Prometheus 데이터 보존 기간은 3일이다.
- WebSocket 세션 수는 실제 사용자 수와 다르다. 현재 구현 기준 강연자는 2개, 청중은 4개의 연결을 생성한다.
- 채팅 처리량은 DB 저장 성공을 기준으로 측정하며 실제 수신 성공을 의미하지 않는다.
- LLM 사용량은 Gemini 청중 분석 호출 이력 저장을 기준으로 측정한다. OpenAI 보고서 생성 호출은 포함하지 않는다.
- LLM 비용은 설정 단가 기반 추정 비용이며 실제 청구 금액과 다를 수 있다.
- 누적 Counter는 애플리케이션 재시작 시 초기화된다.
- 현재 모니터링 환경은 로컬 개발 및 부하 테스트를 대상으로 구성한다.
- 운영 환경 적용 전 Actuator 엔드포인트의 외부 접근 제한 및 내부 네트워크 구성을 검증해야 한다.