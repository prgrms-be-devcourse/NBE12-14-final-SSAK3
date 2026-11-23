# SCRUM-156 보고서 스키마·프롬프트 실험

Spring 전체 강연 요약 서비스와 스키마·프롬프트를 공유하는 Python 실험 도구다. 가상 청중 기록 4개 샘플로 초안 생성과 근거 검증을 확인하며 DB를 읽거나 변경하지 않는다.

- `backend/src/main/resources/report/report-draft.schema.json`: LLM 출력. overview의 세 목록(각 0~3개), 원문/투표 근거, 시간대, 상반 의견.
- `report-content.schema.json`: 검증·병합 후 보고서. 서버가 timeline, limitations, dataLimitations, evidencePolicy를 추가한다. timeline 내부 원문 레코드의 상세 계약은 각 도메인 명세가 담당한다.
- `report-system-prompt.txt`: 한국어 시스템 프롬프트.
- `build_schemas.py`: 두 스키마의 재현 가능한 생성기. 수정 후 재실행하고 JSON 파일을 함께 커밋한다.
- `run.py`: 샘플 변환, 로컬 검증·조립, 선택적 Responses API 호출. 모델은 `--model`로 바꿀 수 있으나 비용 추정은 Luna만 지원한다.


팀원용 [4가지 샘플 보고서와 입력 데이터](../../docs/experiments/report-samples.md)에서 실제 생성 문장과 가상 질문·투표를 함께 확인할 수 있다.

## 준비와 실행 (저장소 루트 PowerShell)

Python 3.10 이상이 필요하다. 기존 Java 런타임이나 Spring 설정은 변경하지 않는다.

```powershell
python -m pip install -r scripts/report_experiment/requirements.txt
python -m unittest discover -s scripts/report_experiment -p 'test_*.py' -v
```

루트 `.env`에 `OPENAI_API_KEY=발급받은 키`를 추가한다. 키는 커밋하거나 명령줄 인자로 전달하지 않는다. Responses Write 권한을 사용한다. OS 환경변수가 있으면 `.env`보다 우선한다.

입력 생성만 실행(호출·과금 없음):

```powershell
python scripts/report_experiment/run.py --source 'C:/Users/문창원/Downloads/새로운 노트 (1).txt' --output .report-experiments/prepare-new
```

실제 실험(각 시나리오 최대 1회, 전체 4회):

```powershell
python scripts/report_experiment/run.py --source 'C:/Users/문창원/Downloads/새로운 노트 (1).txt' --output .report-experiments/luna-new --execute
```

`--output`은 새 디렉터리여야 한다. 기존 실험을 덮어쓰거나 재시도하지 않는다. 새 경로로 재실행하면 별도 유료 실험이다. 자동 재시도·모델 대체 호출은 없다. 토큰 상한 정책은 미확정이므로 이 도구도 별도 상한을 확정하지 않는다. `reasoning.effort=low`, 일반 처리 등급, 도구 미사용, `store=false`로 호출한다.

결과는 `summary.json`과 시나리오별 `input.json`, `attempt.json`, 성공 시 `draft.json`·`report.json`에 저장한다. 로컬 원문·모델 결과는 `.report-experiments/`에 두고 커밋하지 않는다. 실패 시 공급자 오류 원문·키를 출력하지 않으며 세 내부 코드와 HTTP 상태만 기록한다. usage가 없는 실패는 비용을 0이라고 추정하지 않는다. 비용은 2026-11-18 공식 Luna Standard 요금으로 계산한 추정치이며 실제 청구를 대신하지 않는다. 입력 사용량은 일반 입력·캐시 읽기·30분/12시간 캐시 쓰기로 구분해 계산한다. 캐시 쓰기 요금은 일반 입력 요금을 대체하며 추가로 중복 가산하지 않는다. 쓰기 사용량 세부 정보가 불완전하면 비용은 null로 둔다. 출력 토큰에는 추론 토큰이 이미 포함되므로 중복 가산하지 않는다.

요금 근거: [Luna 모델](https://developers.openai.com/api/docs/models/gpt-6-luna), [캐시 과금](https://developers.openai.com/api/docs/guides/prompt-caching). 최초 실험 결과는 `docs/experiments/report-results.json`에 원문 없이 기록한다.

## 입력 및 검증의 범위

- `docs/experiments/report-scenarios.json`의 4개 청중 기록은 가상 데이터다. 실제 청중 데이터로 표시하지 않는다.
- 사용자 전사는 파일 앞의 작성자·날짜를 제외하고 타임스탬프부터 읽는다. 실제 운영 시각 대신 고정 실험 시각을 사용한다. 원문에 발언 종료 시각이 없으므로 null로 유지하고 시작 구간에만 연결한다. 운영 STT 중첩 연결의 대체 구현이 아니다.
- 마감된 투표는 참여자별 마지막 선택으로 집계하고 개인 선택을 LLM 및 완성 보고서에 포함하지 않는다. 일반 투표의 누적 참여자 수는 이 fixture에서 4로 가정한다.
- 누락 사례는 지정 범위에서 시작한 전사 구간을 제거한 가상 장애다. 그 외 분석 준비 완료는 fixture 가정이며 운영 finalization 구현은 별도다. 저장된 AI 요약 재사용 경로는 이 실험에서 실행하지 않는다.
- JSON 스키마, 같은 방의 입력 근거 ID, 마감 여부, 실제 근거 시간대의 합집합을 자동 검증한다. 상반 의견은 각 입장을 먼저 검증한 뒤 상위 근거·시간대를 서버 코드에서 합친다.
- 보고서와 입장별 한계는 실제 fixture 누락·실패 범위로 결정한다. STT만 있거나 기록이 없으면 LLM 호출을 건너뛴다.
- **문장이 근거 의미와 일치하는지는 사람이 원문과 대조해야 한다.** 자동 검증 통과는 의미 정확도 합격이나 모델 채택을 뜻하지 않는다. summary의 `humanReview=PENDING`은 수동 검토 전 상태다.

운영 인증·DB 저장·비동기 실행·중복 요청 제어·기존 AI 분석 재사용은 후속 보고서 구현 범위다. 이 도구의 디렉터리 중복 방지는 운영 API 멱등성 구현을 대신하지 않는다.
