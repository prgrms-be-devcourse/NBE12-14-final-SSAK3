# AI-CHAT — 청중 의견 분석


### [AI-CHAT-01~05, 08] 주기적 청중 채팅 분석

서버 내부 스케줄러와 LLM API 호출. 별도 공개 Request API 는 없다.

실행 조건: LIVE 중 강연 `startedAt` 기준 분석 주기(기본 30초) 도래, 분석 대상 정규 구간 확정. 구간은 `[windowStartAt, windowEndAt)` 이며 청중 채팅은 서버 수신 시각 `receivedAt` 으로 포함 여부를 판단한다. ENDED 전환 후에는 `endedAt`까지 남은 30초 미만 잔여 구간을 한 번 확정해 비동기로 처리한다. 강연 종료 API는 LLM 분석 완료를 기다리지 않으며, 종료 finalization의 호출 주체·방식은 A/C/D 협의 사항이다.

스케줄러 지연 또는 서버 재시작 후에는 `startedAt`부터 현재 시각까지 완전히 닫힌 정규 30초 구간 중 `ai_analyses` 기록이 없는 구간을 오래된 순서로 처리한다. 강연방별 한 실행의 최대 처리 구간 수는 `app.ai.audience.scheduler.max-windows-per-run`(기본 3)이며, 남은 미기록 구간은 다음 실행에서 이어서 처리한다. 이미 분석 행이 있는 구간(`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`)은 이 경로에서 다시 호출하지 않는다. 종료 시 잔여 구간 처리와 FAILED 재시도는 별도 후속 작업이다.

정상 흐름: 채팅 DB 조회 → 강연자 메시지 제외 → 보수적 노이즈 필터링·동일 원문 전처리 → 분석 구간 확정 → `ai_analyses` PENDING 생성 → PROCESSING 변경 → 전처리 후 청중 채팅이 있으면 LLM API 호출·구조화된 결과 검증·유사 주제 그룹화·원문 메시지 연결·결과 저장 → COMPLETED 변경 → 대시보드 알림. 전처리 후 분석 대상이 없는 구간은 LLM 호출 없이 `COMPLETED` 로 변경하며, `model_name` 은 null이고 주제 그룹은 0개다. `COMPLETED` 는 LLM 호출 성공만이 아니라 해당 분석 구간의 정상 처리 완료를 뜻한다.

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
- AI-CHAT-09 전처리는 명백한 반복 이모지·기호·반응성 메시지만 보수적으로 제외한다. 일반적인 짧은 질문·요청·이해 부족 표현은 글자 수를 기준으로 제외하지 않으며, 원본 채팅은 삭제·수정하지 않는다.
- AI-CHAT-10은 Unicode NFC, 앞뒤·연속 공백 정리, 영문 대소문자 통일 뒤 문자열이 같은 메시지만 하나의 대표 입력으로 병합한다. 의미 유사도 기반 병합은 하지 않는다. Gemini에는 대표 `messageId`, `content`, `occurrenceCount`를 전달하며, `occurrenceCount`는 실제 메시지 발생 횟수일 뿐 고유 청중 수·찬성 수·동의자 수를 뜻하지 않는다.
- Gemini가 반환하는 `sourceMessageIds`는 대표 메시지 ID만 사용한다. 서버는 대표 ID 유효성을 먼저 검증한 뒤 연결된 실제 원본 메시지 ID 전체로 확장해 저장하며, 조회 API의 `messageCount`는 확장되어 실제 연결된 원본 메시지 수를 반환한다.
- P0 구현은 Gemini `generateContent` API를 사용한다. 모델은 `GEMINI_MODEL` 환경변수로 주입하며 로컬 예시는 `gemini-3.5-flash-lite`다. API 키는 `GEMINI_API_KEY` 환경변수로만 주입하고 코드·로그·응답에 포함하지 않는다.
- 요청은 Gemini의 JSON MIME type 및 JSON Schema를 사용해 `topics` 구조를 요청한다. 그래도 외부 응답은 신뢰하지 않고 아래의 서버 측 JSON·원문 ID 검증을 반드시 수행한다.
- 30초 구간의 비용과 지연을 제한하기 위해 기본적으로 메시지 100개, 메시지 본문 합계 32,000자, 출력 2,048 토큰, 응답 본문 128,000자로 제한한다. 상한 초과 시 메시지를 잘라내거나 일부만 전송하지 않고 해당 분석을 FAILED로 기록한다. 향후 배치 분할은 별도 정책으로 정한다.
- 물음표 없는 의견도 분석 대상이다. 같은 주제의 서로 다른 유형을 그룹화할 수 있다.
- 서로 반대되는 의견을 하나의 합의된 의견으로 왜곡하지 않고, 원문에 없는 의견을 생성하지 않는다.
- LLM 구조화 출력의 최상위 필드는 `topics`만 허용하며, 빈 `topics: []`는 유효한 결과다. 각 topic은 `topic`, `summary`, `category`, `sourceMessageIds` 필드만 가진다. `topic`과 `summary`는 공백이 아니어야 하고, `topic`은 최대 200자다. `category`는 위 표의 값만 허용한다.
- `sourceMessageIds` 는 비어 있지 않은 양의 정수 배열이며, 같은 그룹 안에서 중복될 수 없다. 실제 DB 메시지와 대조해 다른 강연방·분석 구간 밖 메시지 참조를 차단하고, P0에서는 하나의 메시지를 최대 하나의 주제 그룹에만 연결한다.
- 대표 질문 전용 컬럼은 없다. 청중 메시지가 없는 구간도 분석 이력 누락과 정상 생략을 구분할 수 있도록 `ai_analyses` 에 기록하되, 불필요한 LLM 호출은 하지 않는다.
- 청중 메시지가 있는 분석은 사용한 모델명을 저장하며, 모델명은 비어 있지 않고 최대 100자다.
- API 실패 시 FAILED 처리하며 AI 실패가 채팅·이해도·투표에 영향을 주지 않는다.
- 중복 분석 제약: `UNIQUE(lecture_room_id, window_start_at, window_end_at)` — 같은 구간을 중복 분석하지 않도록 작업 상태를 관리한다.
- 분석 대상 시각은 브라우저 시각이 아니라 서버가 기록한 채팅 `receivedAt`을 사용한다. `startedAt`, `endedAt`, `receivedAt`과 분석 구간의 DB `DATETIME`은 애플리케이션 공통 시간대(현재 Asia/Seoul)로 일관되게 해석한다. 후속 스케줄러는 서버 기본 시간대에 의존하지 않고 같은 `ZoneId`와 저장된 `startedAt`을 사용해 경계를 계산하며, `[start, end)` 비교를 바꾸지 않는다. 외부 API의 시각은 공통 규약대로 UTC 오프셋을 포함한 ISO-8601로 표현한다.

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

