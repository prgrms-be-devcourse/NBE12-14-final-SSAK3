# AI-CHAT — 청중 의견 분석


### [AI-CHAT-01~05, 08] 주기적 청중 채팅 분석

서버 내부 스케줄러와 LLM API 호출. 별도 공개 Request API 는 없다.

실행 조건: 강연 LIVE, 분석 주기 도래, 분석 대상 청중 채팅 존재, 분석 대상 구간 확정.

정상 흐름: 채팅 DB 조회 → 강연자 메시지 제외 → 분석 구간 확정 → `ai_analyses` PENDING 생성 → PROCESSING 변경 → LLM API 호출 → 구조화된 결과 검증 → 유사 주제 그룹화·핵심 의견 요약 → 원문 메시지 연결 → 결과 DB 저장 → COMPLETED 변경 → 대시보드 알림.

LLM 출력 예시

```json
{
  "topics": [
    {
      "topic": "Redis 사용 이유",
      "summary": "Redis를 MySQL 대신 사용하는 이유가 궁금하다는 질문과 실제 사례를 요청하는 의견이 있었습니다.",
      "category": "MIXED",
      "sourceMessageIds": [1001, 1003]
    }
  ]
}
```

| category | 설명 |
| --- | --- |
| QUESTION | 질문 중심 |
| OPINION | 의견 중심 |
| REQUEST | 요청·제안 중심 |
| CONFUSION | 이해 부족 표현 중심 |
| MIXED | 여러 유형이 섞인 주제 |

규칙:

- AI 입력은 청중이 작성한 메시지로 한정하고 강연자 메시지는 제외한다.
- 물음표 없는 의견도 분석 대상이다. 같은 주제의 서로 다른 유형을 그룹화할 수 있다.
- 서로 반대되는 의견을 하나의 합의된 의견으로 왜곡하지 않고, 원문에 없는 의견을 생성하지 않는다.
- `sourceMessageIds` 는 실제 DB 메시지와 대조하며, 다른 강연방·분석 구간 밖 메시지 참조를 차단한다.
- 대표 질문 전용 컬럼은 없다. 메시지가 없는 구간의 불필요한 호출은 생략을 권장한다.
- API 실패 시 FAILED 처리하며 AI 실패가 채팅·이해도·투표에 영향을 주지 않는다.
- 중복 분석 제약: `UNIQUE(lecture_room_id, window_start_at, window_end_at)` — 같은 구간을 중복 분석하지 않도록 작업 상태를 관리한다.

엔터티: `ai_analyses`, `ai_topic_groups`, `ai_topic_group_messages`

### [AI-CHAT-06] AI 분석 결과 조회

**GET** `/api/v1/rooms/{roomId}/ai/analyses?limit=10` — HOST, 강연방 소유자 (limit 기본 10, 최대 50 제안)

```json
{
  "data": {
    "roomId": 10,
    "analyses": [
      {
        "analysisId": 301,
        "windowStartAt": "2026-11-17T01:01:30Z",
        "windowEndAt": "2026-11-17T01:02:00Z",
        "status": "COMPLETED",
        "completedAt": "2026-11-17T01:02:08Z",
        "topicGroups": [
          {
            "topicGroupId": 401,
            "topic": "Redis 사용 이유",
            "category": "MIXED",
            "summary": "Redis 도입 이유와 활용 사례에 대한 질문과 의견이 있었습니다.",
            "messageCount": 2,
            "sourceMessageIds": [1001, 1003]
          }
        ]
      }
    ],
    "lastCompletedAt": "2026-11-17T01:02:08Z"
  }
}
```

규칙: 최신·이전 분석 결과 조회, 강연방 소유권 검증, `messageCount` 는 실제 연결된 원문 수로 계산, 분석 실패 시 상태 표시, 내부 오류 상세는 반환하지 않음.

### [AI-CHAT-07, DASH-05] AI 의견 요약 관련 원문 조회

**GET** `/api/v1/rooms/{roomId}/ai/topic-groups/{topicGroupId}/messages` — HOST, 강연방 소유자

```json
{
  "data": {
    "topicGroupId": 401,
    "topic": "Redis 사용 이유",
    "messages": [
      { "messageId": 1001, "participantId": 101, "nickname": "청중A", "content": "Redis를 꼭 사용해야 하나요?", "receivedAt": "2026-11-17T01:01:40Z" },
      { "messageId": 1003, "participantId": 102, "nickname": "청중B", "content": "MySQL만으로 처리하는 예시도 보여 주세요.", "receivedAt": "2026-11-17T01:01:50Z" }
    ]
  }
}
```

규칙: 주제별 요약의 근거 원문 조회, 같은 강연방의 메시지만 반환, AI 요약 문장을 원문으로 표시하지 않음, 연결 테이블로 메시지를 조회한다.
엔터티: `ai_topic_groups`, `ai_topic_group_messages`, `chat_messages`

