# FEED — 이해도 신호


### [FEED-01, 02, 03, 05] 이해 상태 등록 및 변경

**PUT** `/api/v1/rooms/{roomId}/understanding/me` — PARTICIPANT

Request

```json
{ "status": "CONFUSED" }
```

| 값 | 의미 |
| --- | --- |
| UNDERSTOOD | 이해됨 |
| CONFUSED | 헷갈림 |
| NOT_UNDERSTOOD | 모르겠음 |

Response — 200 OK

```json
{
  "participantId": 101,
  "roomId": 10,
  "previousStatus": "UNDERSTOOD",
  "currentStatus": "CONFUSED",
  "changed": true,
  "updatedAt": "2026-11-17T01:12:00Z"
}
```

규칙:

- LIVE 에서만 변경 가능하며, 한 청중의 최신 이해 상태는 하나만 존재한다.
- 최초 선택 시 `participant_understandings` 를 생성하고, 이후 변경은 기존 레코드를 수정한다.
- 실제 변경이 발생하면 `understanding_histories` 를 추가한다.
- 동일 상태를 다시 요청하면 `changed=false` 이며 이력·응답 인원이 증가하지 않는다.
- 최신 상태 변경과 이력 저장은 하나의 트랜잭션이며, 동시 요청에도 정합성을 보장한다.

엔터티: `participant_understandings`, `understanding_histories`

### [FEED-02] 내 최신 이해 상태 조회

**GET** `/api/v1/rooms/{roomId}/understanding/me` — PARTICIPANT

```json
{ "participantId": 101, "status": "CONFUSED", "updatedAt": "2026-11-17T01:12:00Z" }
```

미응답이면 `{ "participantId": 101, "status": null, "updatedAt": null }`. 미선택은 모르겠음으로 간주하지 않는다. 자신의 최신 상태만 조회하며 재접속 시 복원에 활용할 수 있다.

### [FEED-04, 06] 강연방 이해도 집계 조회

**GET** `/api/v1/rooms/{roomId}/understanding/summary` — HOST, 강연방 소유자

```json
{
  "roomId": 10,
  "totalParticipants": 20,
  "respondedParticipants": 15,
  "unrespondedParticipants": 5,
  "responseRate": 75.0,
  "counts": { "UNDERSTOOD": 8, "CONFUSED": 5, "NOT_UNDERSTOOD": 2 },
  "percentagesOfAllParticipants": { "UNDERSTOOD": 40.0, "CONFUSED": 25.0, "NOT_UNDERSTOOD": 10.0 },
  "calculatedAt": "2026-11-17T01:12:01Z"
}
```

규칙: 전체 인원은 누적 고유 청중 기준, 응답 인원은 최신 이해 상태가 있는 고유 청중 수, 미응답 = 전체 − 응답, 상태 비율의 분모는 전체 참여 인원, 과거 변경 이력은 현재 집계에 중복 포함하지 않음, 현재 접속 인원과 누적 참여 인원은 구분한다.
엔터티: `lecture_participants`, `participant_understandings`

