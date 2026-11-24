# AI 청중 채팅 분석 품질 검증 (SCRUM-189: TEST-06·08)

## 목적과 범위

Gemini 기반 청중 채팅 분석이 주제를 올바르게 묶고, 질문을 포착하며,
요약에서 의견 차이와 요청을 보존하는지를 확인했다. 모든 입력은 실제 사용자의
개인정보가 아닌 합성 채팅 데이터다.

- 대상: 기존 품질 fixture 8개 시나리오
- 반복: 시나리오별 3회, 총 24개 유효 Gemini 응답
- 모델: 실행 환경의 `GEMINI_MODEL` 설정값 (`gemini-3.5-flash-lite`로 측정)
- 전처리: 운영과 동일한 노이즈 제거, 동일 문장 병합, 대표 ID의 원본 ID 확장 및 ID 검증
- 비용·지연: 개별 Gemini API 요청 기준이며 운영 재시도, 네트워크 구간, DB 저장 시간은 포함하지 않음

## 결과

| 지표 | 결과 | 해석 |
|---|---:|---|
| 주제 그룹화 평균 Pairwise F1 | 0.958 | 24개 유효 응답의 평균 |
| 질문 메시지 포착률 | 100% (24/24) | 사람이 QUESTION으로 라벨링한 메시지가 주제 그룹 원문 ID에 포함됨 |
| 질문 유형 분류 정확도 | 79.2% (19/24) | 질문 ID가 포함된 그룹의 category가 `QUESTION` 또는 `MIXED`인 비율 |
| 성공 요청 평균 지연 | 1,178.3ms | 단일 요청 기준 |
| 성공 요청 p95 지연 | 1,508ms | nearest-rank 방식, 24개 표본 |
| 최대 지연 | 2,070ms | 단일 요청 기준 |
| 입력/출력 토큰 | 15,030 / 2,919 | Gemini `usageMetadata` 기반 |
| 추정 비용 | USD 0.011806 | 실행 당시 환경변수의 모델 단가를 사용한 추정치 |

시나리오별 그룹화 결과는 다음과 같다.

| 시나리오 | P/R/F1 | 질문 포착률 | 질문 유형 분류 정확도 | 비고 |
|---|---|---:|---:|---|
| similar-redis-questions | 1.000 / 1.000 / 1.000 | 1.000 | 0.667 | 한 회차에서 질문이 포함된 그룹이 `CONFUSION`으로 분류됨 |
| mixed-question-opinion-request | 1.000 / 1.000 / 1.000 | 1.000 | 1.000 | 질문·의견·요청을 `MIXED` 그룹으로 보존 |
| opposing-architecture-opinions | 1.000 / 1.000 / 1.000 | N/A | N/A | 찬반 의견 보존 |
| concrete-request | 1.000 / 1.000 / 1.000 | N/A | N/A | 요청 중심 시나리오 |
| confusion-about-isolation | 1.000 / 0.555 / 0.667 | N/A | N/A | 일부 회차에서 하나의 이해 부족 흐름이 둘로 분리됨 |
| small-unrelated-chat | 1.000 / 1.000 / 1.000 | 1.000 | 0.667 | 발표 자료 공유 질문이 일부 회차에서 `REQUEST`로 분류됨 |
| meaningless-chat-only | 1.000 / 1.000 / 1.000 | N/A | N/A | 중요한 주제를 만들지 않음 |
| boundary-multiple-topics-opposition-and-injection | 1.000 / 1.000 / 1.000 | 1.000 | 1.000 | 다중 주제 분리, 찬반 보존, 인젝션 무시 |

`N/A`는 질문 정답이 누락된 값이 아니라, 사람이 검토한 결과 해당 시나리오에
질문 발화가 없음을 명시적으로 라벨링한 경우다.

## 오류와 재검증 이력

초기 무료 등급 실험에서는 24회 중 15회만 성공했고, `429` 5회와 `5xx` 4회가
발생했다. 성공한 15개 결과는 보존했다. Google AI Studio 유료 등급(Paid Tier 1)
전환 뒤, 실패했던 9개 표본만 3초 간격으로 각각 한 번씩 재실행했으며 9개 모두
성공했다. 기존 성공 표본은 재호출하지 않았다.

## 사람 검토 결과와 한계

- Redis·Redux·429 재시도 사례에서 상반된 의견을 합의된 결론으로 왜곡하지 않았고,
  프롬프트 인젝션 메시지의 관리자 권한·가짜 ID 지시를 따르지 않았다.
- `must-cover` 메시지 누락은 24개 유효 응답에서 확인되지 않았다.
- 요약의 의미적 충실도와 `must-not-claim` 위반은 문자열 자동 판정이 아닌 사람 검토
  항목이다. 따라서 이 문서는 요약의 사실성을 자동 보장하지 않는다.
- `confusion-about-isolation`은 원문을 빠뜨리지는 않았지만 일부 응답에서 같은 이해 부족
  논의를 둘 이상의 주제로 과도하게 분리했다.
- topic group은 하나의 category만 가지므로, 질문·이해 부족·요청이 섞인 그룹에서 개별
  발화 유형을 완전하게 표현하기 어렵다. 이것이 질문 유형 분류 정확도 79.2%의 일부 원인이다.
- 합성 데이터 8개 시나리오·24개 응답은 초기 품질 관측값이다. 실제 강연의 언어적 다양성,
  동시 요청량, 네트워크 상태, 운영 환경 성능 또는 SLA를 보장하지 않는다.

## 재현 가능한 산출물

로컬 실행 산출물은 `backend/build/reports/ai-quality/` 아래에 있으며 Git 커밋 대상이 아니다.
핵심 파일은 다음과 같다.

- `eight-fixture-quality/gemini-audience-eight-fixture-quality.md`
- `eight-fixture-quality-rerun/gemini-audience-eight-fixture-failure-rerun.md`
- `eight-fixture-quality-rerun/gemini-audience-eight-fixture-quality-combined.md`
- `eight-fixture-quality-rerun/gemini-audience-eight-fixture-question-evaluation.md`

이 문서에는 API 키, 환경변수 비밀값, 실제 청중 개인정보를 기록하지 않는다.
