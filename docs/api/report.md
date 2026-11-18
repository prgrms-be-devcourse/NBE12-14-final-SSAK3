# REPORT — AI 회고 보고서


### [REPORT-01] AI 회고 보고서 생성 요청

**POST** `/api/v1/rooms/{roomId}/reports` — HOST, 강연방 소유자 — Request `{}`

Response — 202 Accepted

```json
{
  "data": {
    "reportId": 601,
    "roomId": 10,
    "status": "PENDING",
    "createdAt": "2026-11-17T02:00:10Z",
    "message": "회고 보고서 생성 요청이 접수되었습니다."
  }
}
```

실행 조건: 강연 ENDED, 마지막 STT 발언 저장 완료, 투표 응답 집계 확정, 강연 `startedAt` 기준 모든 30초 AI 정규 구간과 종료 시 잔여 구간이 `COMPLETED` 또는 `FAILED` 로 확정, 요청자가 강연방 소유자. 청중 채팅이 없는 구간은 LLM 호출 없이 주제 그룹 0개의 `COMPLETED` 분석으로 기록되므로 분석 누락으로 보지 않는다. 보고서 요청은 AI 분석을 새로 생성하거나 재호출하지 않는다. 종료 후 잔여 분석 finalization의 호출 주체·방식은 A/C/D 협의 사항이다.
규칙: 종료된 강연만 생성, 강연방당 보고서 하나를 기본으로 관리, 중복 생성 요청 시 기존 보고서 ID·상태 반환(LLM 중복 호출 방지), AI 보고서 생성은 비동기, 데이터 준비가 끝나지 않았으면 생성 거부, 실패 시 FAILED 기록.
오류: `403 FORBIDDEN`, `404 ROOM_NOT_FOUND`, `409 ROOM_NOT_ENDED`, `409 REPORT_INPUT_NOT_READY` · 엔터티: `lecture_reports`

### [REPORT-02, 03, 04, 05, 07] 보고서 내부 데이터 처리

별도 외부 Request API 는 없다.

수집 대상: `stt_segments`(실제 발언), `stt_gaps`(누락 구간), `chat_messages`(청중 채팅 원문), `ai_analyses`·`ai_topic_groups`(저장된 분석 구간과 주제별 의견 요약), `polls`·`poll_options`·`poll_responses`(일반 투표와 이해도 체크 `type=UNDERSTANDING` 의 마감된 결과).

정상 흐름: 강연 데이터 조회 → 시간순 정렬 → 60초 보고서 구간 생성 → STT 발언 연결 → 이해도 체크 결과 연결 → 청중 채팅과 저장된 30초 AI 분석 결과 연결 → 투표 결과 연결 → LLM 회고 요약 생성 → AI 결과·근거 검증 → 구조화된 보고서 JSON 저장. 보고서 생성 과정은 청중 채팅 분석 LLM을 다시 호출하지 않는다.

시간대별 연결 기준

| 데이터 | 연결 기준 |
| --- | --- |
| STT 발언 | 발언 추정 시작·종료 시각 |
| 채팅 | 서버 수신 시각 |
| 이해도 체크 | 투표를 연 시각(`created_at`)이 속한 구간에 연결하고 `openedAt`·`closedAt` 을 함께 기록 |
| 일반 투표 | 생성·종료 및 응답 시각 |
| AI 의견 요약 | 같은 `startedAt` 기준 30초 분석 구간과 근거 메시지 시각 |

기본 구간: 보고서는 강연 `startedAt` 기준 60초 단위 `[start, end)`로 분할한다. AI 분석은 같은 `startedAt` 기준 30초 `[start, end)` 구간이므로, 하나의 정규 보고서 구간에는 시간 범위가 포함되는 AI 분석 구간 결과 두 개를 연결한다. 종료 시 30초 미만 잔여 분석 구간은 마지막 보고서 구간에 연결한다. 보고서는 저장된 `ai_analyses`·`ai_topic_groups`를 재사용하며, 빈 분석 구간의 `COMPLETED`는 그룹 0개로 표현한다.

보고서 JSON 예시

```json
{
  "overview": "Redis와 MySQL의 역할 차이에 대한 의견이 반복되었습니다.",
  "timeline": [
    {
      "startAt": "2026-11-17T01:12:00Z",
      "endAt": "2026-11-17T01:13:00Z",
      "sttSegments": [
        { "segmentId": 9001, "transcript": "Redis는 메모리 기반 데이터 저장소입니다." }
      ],
      "sttGaps": [],
      "understandingChecks": [
        {
          "pollId": 502,
          "openedAt": "2026-11-17T01:12:20Z",
          "closedAt": "2026-11-17T01:13:20Z",
          "totalParticipants": 20,
          "respondedParticipants": 16,
          "counts": { "UNDERSTOOD": 8, "CONFUSED": 6, "NOT_UNDERSTOOD": 2 },
          "lackCount": 8,
          "lackRatio": 50.0,
          "lowUnderstanding": true
        }
      ],
      "audienceOpinions": [
        {
          "topic": "Redis 사용 이유",
          "category": "MIXED",
          "summary": "Redis 도입 이유를 궁금해하는 의견이 있었습니다.",
          "topicGroupIds": [401],
          "sourceMessageIds": [1001, 1003]
        }
      ],
      "polls": [
        { "pollId": 501, "type": "GENERAL", "question": "Redis가 필요한 이유를 이해했나요?", "respondedParticipants": 12 }
      ],
      "aiInterpretation": "이 구간에서 이해 부족 반응이 관찰되어 보충 설명을 검토할 수 있습니다."
    }
  ],
  "dataLimitations": ["STT 전사 시각은 브라우저 추정값입니다."],
  "evidencePolicy": "동일 시간대의 관찰은 인과관계를 의미하지 않습니다."
}
```

핵심 규칙:

- 보고서는 실제 수집된 데이터로 생성하며, 강연 발언 원문을 LLM 이 임의로 생성하지 않는다.
- STT 누락 구간은 '발언 기록 없음'으로 표시한다. 1초 미만 구간을 개별 표시에서 제외할지는 모의 강연 후 결정한다.
- 이해도 체크는 마감된 라운드의 최종 응답 기준이며, 선택하지 않은 청중은 미응답으로 두고 모르겠음으로 간주하지 않는다. 체크를 열지 않은 구간에는 이해도 데이터가 없음을 그대로 표시하고 보완하지 않는다.
- AI 해석과 실제 기록을 다른 필드로 구분한다.
- 청중 의견에 서로 다른 입장이 있으면 모두 보존하고, 질문이 없어도 의견·요청·반응을 분석에 포함한다.
- 관련 원문 ID 는 실제 DB 데이터와 일치해야 한다.
- 같은 시간대라는 이유로 발언과 반응 사이의 인과관계를 단정하지 않는다.

### [REPORT-06] AI 회고 보고서 조회

**GET** `/api/v1/rooms/{roomId}/reports` — HOST, 강연방 소유자

완료(200 OK)

```json
{
  "data": {
    "reportId": 601,
    "roomId": 10,
    "status": "COMPLETED",
    "createdAt": "2026-11-17T02:00:10Z",
    "generatedAt": "2026-11-17T02:00:30Z",
    "content": {
      "overview": "Redis와 MySQL의 역할 차이에 대한 의견이 반복되었습니다.",
      "timeline": [ { "startAt": "2026-11-17T01:12:00Z", "endAt": "2026-11-17T01:13:00Z", "sttSegments": [], "sttGaps": [], "understandingChecks": [], "audienceOpinions": [], "polls": [], "aiInterpretation": "해당 구간의 보충 설명을 검토할 수 있습니다." } ],
      "dataLimitations": [],
      "evidencePolicy": "시간적 동시성은 인과관계를 의미하지 않습니다."
    }
  }
}
```

생성 중(200 OK): `{ "data": { "reportId": 601, "status": "PROCESSING", "content": null, "generatedAt": null } }`
생성 실패(200 OK): `{ "data": { "reportId": 601, "status": "FAILED", "content": null, "errorCode": "LLM_REQUEST_FAILED" } }`

규칙: 보고서가 없으면 404, 생성 중이면 PROCESSING, 실패면 FAILED, 완료는 저장된 JSON 반환, **조회 시 LLM API 를 재호출하지 않음**, 다른 강연방 보고서 조회 차단.

