# 여러 강연 동시 종료 부하 테스트

서로 다른 강사가 강연방을 동시에 종료할 때 종료 버튼의 응답 시간과 회고 보고서가 완성되기까지 걸리는 시간을 측정한다.

## 실행

JDK 25, Python 3.10 이상, k6를 준비한다. Docker를 켜고 저장소 루트에서 다음 명령을 실행한다.

```powershell
python infra/k6/report_load.py 40
```

마지막 숫자는 **동시에 종료할 강연방 수**다. `1`은 방 하나의 기준 시간, `10`과 `40`은 여러 방이 동시에 끝나는 상황을 측정한다.

명령 하나로 테스트 전용 MySQL·Redis, 백엔드, 모의 OpenAI 서버가 시작된다. 방 한 개를 먼저 종료해 예열한 뒤, 선택한 수만큼 새 방을 만들어 본 측정을 진행한다. 방마다 녹음 중인 STT 세션, 확정 발언 한 건, 청중 질문 한 건, 분석을 마친 30초 구간이 있다. 실제 강연 종료 API와 마지막 분석 확정 스케줄러를 거치며, 동기식 보고서 생성 단계에서 로컬 모의 서버가 요청마다 1초 뒤 응답한다. 실행이 끝나면 전용 서비스가 종료된다.

## Grafana에서 보기

저장소 루트에서 모니터링 구성을 한 번 적용한다. 기존 Grafana와 Prometheus가 실행 중이어도 같은 명령으로 대시보드와 수신 설정을 반영할 수 있다.

```powershell
docker compose -f infra/monitoring/compose.monitoring.yaml up -d --no-deps report-results prometheus grafana
```

[보고서 부하 테스트 대시보드](http://localhost:3000/d/react-report-load)에서 11월 27일에 저장한 8회 측정값을 볼 수 있다. 새 결과도 본 측정 폴더의 k6 요약과 DB 검증 파일이 작성되면 표시된다. 회차별 종료 API와 보고서 완료 p95, 마지막 청중 분석 확정에 걸린 시간, 보고서 검증 건수를 함께 비교할 수 있다.

모니터링이 준비된 상태에서 평소처럼 `python infra/k6/report_load.py 40`을 실행하면 본 측정의 k6 지표가 실시간으로 전송된다. 방 개수는 마지막 숫자로 정한다. 실행 중에는 끝난 방의 샘플이 들어올 때마다 완료 지연이 갱신된다. 짧은 실행에서는 마지막 전송에서 처음 값이 보일 수 있다. 저장된 회차의 최종 p95는 k6 전체 실행 요약 기준이다. 예열 결과는 대시보드에서 제외한다.

## 결과 읽기

결과는 실행마다 `backend/build/reports/report-end/<run-id>/`에 저장된다.

| 파일 | 확인할 내용 |
| --- | --- |
| `k6-summary.json` | `room_end_ms`는 종료 API 응답 시간, `report_completed_observed_ms`는 종료 요청부터 상태 API에서 완료를 처음 관측하기까지 걸린 시간이다. 각각 `p(95)`와 `max`를 확인한다. |
| `db-verification.json` | `verified`가 선택한 방 수와 같고 `failures`가 비어 있으면 모든 방에서 STT 종료, 발언·질문을 포함한 보고서 한 건, 모의 AI 생성 요청 한 건을 확인한 것이다. `dbPhaseSeconds`에는 종료 → 마지막 분석 확정 → 보고서 저장의 구간별 시간이 담긴다. |
| `backend.log` | 지연·실패가 생겼을 때 보고서 생성 대기열 경고와 서버 오류를 확인한다. |

먼저 `metrics.report_completed_rate.value`가 `1`인지 확인한다. 일부 방이 실패해도 완료한 방의 지연 통계는 남는다. k6는 상태 API를 1초 간격으로 읽으므로 완료 관측 시각은 DB 저장 시각보다 늦을 수 있다. DB 구간 시간은 초 단위다. 예열 결과는 `warmup/`에 따로 보관한다. [2026-11-27 로컬 측정 결과](../../docs/experiments/report-end-load-2026-11-27.json)

이전 비교 실험을 재현하려면 PowerShell에서 `$env:AI_FINALIZATION_MAX_ROOMS_PER_RUN = '40'`을 설정한 뒤 같은 40개 방 명령을 실행한다. 기본 조건은 스케줄러 실행당 5개 방이다. 비교 후 `Remove-Item Env:AI_FINALIZATION_MAX_ROOMS_PER_RUN`으로 기본 조건을 복원한다.

## 이미 실행한 서버에서 한 번 더 측정하기

테스트 전용 백엔드(`18640`), 모의 OpenAI(`18641`), MySQL(`18643`), Redis(`18644`)를 수동으로 띄워 둔 경우에만 아래 명령을 사용한다. 매번 새 방을 준비하고, k6와 DB 검증 결과를 각각 확인한다.

```powershell
$runDir = (New-Item -ItemType Directory -Path (Join-Path $env:TEMP ([guid]::NewGuid().ToString()))).FullName
$fixture = Join-Path $runDir 'rooms.json'
python infra/k6/report_fixture.py prepare --rooms 40 --file $fixture
if ($LASTEXITCODE -ne 0) { throw '테스트 방 준비 실패' }
k6 run -e "FIXTURE_FILE=$fixture" -e POLL_MS=1000 --summary-export "$runDir/k6-summary.json" infra/k6/report-end.js
$loadExit = $LASTEXITCODE
python infra/k6/report_fixture.py verify --file $fixture | Tee-Object -FilePath "$runDir/db-verification.json"
if ($LASTEXITCODE -ne 0 -or $loadExit -ne 0) { throw '부하 테스트 또는 DB 검증 실패' }
```
