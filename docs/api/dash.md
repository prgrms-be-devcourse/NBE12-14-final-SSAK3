# DASH — 실시간 대시보드


별도 대시보드 엔터티는 없다. 기존 API 의 조회 결과와 WebSocket 이벤트를 조합해 화면을 구성한다.

### 9-1. 기능 매핑

| 기능 ID | 기능 | 데이터 조회 API | 실시간 이벤트 |
| --- | --- | --- | --- |
| DASH-01 | 실시간 채팅 | GET `/rooms/{roomId}/messages` | CHAT_CREATED |
| DASH-02 | 현재 이해도 분포 | GET `/rooms/{roomId}/understanding/summary` | UNDERSTANDING_SUMMARY_UPDATED |
| DASH-03 | 투표 현황 | GET `/rooms/{roomId}/polls` 및 결과 조회 | POLL_CREATED / POLL_RESULTS_UPDATED |
| DASH-04 | AI 주제별 의견 요약 | GET `/rooms/{roomId}/ai/analyses` | AI_ANALYSIS_COMPLETED |
| DASH-05 | 관련 채팅 원문 | GET `/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages` | 별도 이벤트 불필요 |

표의 REST 경로는 공통 Base URL `/api/v1` 을 생략했다.

### 9-2. 이해도 집계 갱신 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/understanding`

```json
{
  "type": "UNDERSTANDING_SUMMARY_UPDATED",
  "data": {
    "roomId": 10,
    "totalParticipants": 20,
    "respondedParticipants": 15,
    "unrespondedParticipants": 5,
    "counts": { "UNDERSTOOD": 8, "CONFUSED": 5, "NOT_UNDERSTOOD": 2 },
    "percentagesOfAllParticipants": { "UNDERSTOOD": 40.0, "CONFUSED": 25.0, "NOT_UNDERSTOOD": 10.0 }
  }
}
```

### 9-3. 투표 생성 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls`

```json
{
  "type": "POLL_CREATED",
  "data": {
    "pollId": 501,
    "roomId": 10,
    "question": "Redis가 필요한 이유를 이해했나요?",
    "status": "OPEN",
    "options": [
      { "optionId": 1, "content": "이해했어요" },
      { "optionId": 2, "content": "조금 헷갈려요" }
    ]
  }
}
```

### 9-4. 투표 결과 갱신 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/polls`

```json
{
  "type": "POLL_RESULTS_UPDATED",
  "data": { "pollId": 501, "respondedParticipants": 12, "counts": { "1": 6, "2": 4, "3": 2 } }
}
```

### 9-5. AI 분석 완료 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/ai`

```json
{
  "type": "AI_ANALYSIS_COMPLETED",
  "data": {
    "analysisId": 301,
    "roomId": 10,
    "completedAt": "2026-11-17T01:02:08Z",
    "topicGroups": [
      { "topicGroupId": 401, "topic": "Redis 사용 이유", "category": "MIXED", "summary": "Redis 사용 이유와 실제 사례에 대한 의견이 있었습니다.", "messageCount": 2 }
    ]
  }
}
```

### 9-6. 강연 상태 변경 이벤트 — SUBSCRIBE `/topic/rooms/{roomId}/status`

```json
{ "type": "ROOM_STATUS_CHANGED", "data": { "roomId": 10, "status": "ENDED", "changedAt": "2026-11-17T02:00:00Z" } }
```

### WebSocket 보안 규칙

- 강연방별 채널을 분리한다.
- 청중은 본인이 참여한 강연의 허용된 채널만 구독할 수 있다.
- 강연자 전용 `ai`, `understanding` 채널은 강연방 소유자에게만 허용한다.
- 구독 시에도 서버에서 권한을 검증하고, 청중에게 비공개 집계 데이터가 노출되지 않게 한다.
- 순수 WebSocket 을 사용하면 경로 대신 메시지 `type` 으로 구분하는 방식으로 바꾼다.

