# STT — 강연자 발언 기록


### [STT-01] STT 세션 시작

세션의 시작·종료 시각은 기존 배포 기준과 동일하게 DB의 `DATETIME`에 고정 오프셋 `+09:00`으로 기록한다. API는 이를 UTC로 반환하며, 진행 중인 세션의 `endedAt`은 `null`이다.

**POST** `/api/v1/rooms/{roomId}/stt/sessions` — HOST, 강연방 소유자 — Request `{ "startKey": "9fad8ee6-dd0b-4402-9817-8da1b7144fe5" }`

```json
{ "data": { "sttSessionId": 701, "roomId": 10, "status": "RECORDING", "startedAt": "2026-11-17T01:00:02Z", "endedAt": null, "canRecord": true } }
```

규칙: LIVE 여야 함, 마이크 권한 확인은 프론트에서 수행, 브라우저 Web Speech API 로 STT 시작, 강연방당 동시에 하나의 RECORDING 세션만 허용, 중지 후 재시작하면 새 세션 생성 가능. 엔터티: `stt_sessions`

시작할 때 새 UUID를 만들고 재시도에는 같은 키를 사용한다. 최초 생성은 `201`, 재시도는 기존 세션의 현재 상태와 `200`을 반환한다. 다른 키로 요청했을 때 이미 기록 중이면 `200`, `canRecord: false`를 반환한다. 마이크는 `canRecord: true`일 때만 켠다. 종료된 세션의 시작 키를 재전송해도 재시작하지 않는다.

### [STT-02, 03] STT 확정 발언 저장

**POST** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/segments` — HOST, 강연방 소유자

Request

```json
{
  "startKey": "9fad8ee6-dd0b-4402-9817-8da1b7144fe5",
  "segmentKey": "browser-701-0001",
  "transcript": "Redis는 메모리 기반 데이터 저장소입니다.",
  "segmentStartAt": "2026-11-17T01:00:12Z",
  "segmentEndAt": "2026-11-17T01:00:19Z"
}
```

Response — 201 Created

```json
{
  "data": {
    "segmentId": 9001,
    "sttSessionId": 701,
    "segmentKey": "browser-701-0001",
    "transcript": "Redis는 메모리 기반 데이터 저장소입니다.",
    "segmentStartAt": "2026-11-17T01:00:12Z",
    "segmentEndAt": "2026-11-17T01:00:19Z",
    "receivedAt": "2026-11-17T01:00:20Z"
  }
}
```

강연방 소유권과 세션의 소속을 확인한 뒤, 요청의 `startKey`가 해당 세션을 시작한 키와 일치하는지 검증한다. 발언은 브라우저에서 확정된 전사만 전송한다. 공백뿐인 전사는 거부하고 발언 시작 시각은 종료 시각과 같거나 빨라야 한다.

- 새 `segmentKey`는 강연이 `LIVE`이고 세션이 `RECORDING`일 때 저장하고 `201`을 반환한다. 종료 후 신규 저장은 `409`로 거부한다.
- 같은 세션의 같은 `segmentKey`로 전사 텍스트와 발언 시작·종료 시각이 같은 요청이 오면, 종료 후에도 최초 저장 결과를 `200`으로 반환한다. 서버 수신 시각 `receivedAt`은 비교에서 제외하고 최초 값을 유지한다.
- 같은 키의 전사 내용이나 발언 시각이 다르면 `409`를 반환하고 기존 기록을 유지한다.
- 발언 시작·종료 시각은 밀리초 정밀도로 저장하고 비교한다. UTC 오프셋이 달라도 같은 순간이면 동일한 시각이다.
- 전사 구간의 발언 시작·종료 및 서버 수신 시각은 DB의 `DATETIME`에 고정 오프셋 `+09:00` 기준으로 기록한다. 조회 시 같은 오프셋으로 해석해 API에는 UTC 시각으로 반환한다.
- 진행 중인 강연과 세션에서는 앞선 발언이 늦게 도착해도 저장한다. 회고 타임라인은 `segmentStartAt`을 기준으로 구성하고 `receivedAt`에는 서버의 최초 수신 시각을 남긴다.
제약: `UNIQUE(stt_session_id, segment_key)` · 엔터티: `stt_segments`

### [STT-04] STT 누락 구간 시작 기록

**POST** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/gaps` — HOST, 강연방 소유자

Request

```json
{ "eventKey": "stt-701-gap-0001", "reason": "ERROR", "startedAt": "2026-11-17T01:25:00Z" }
```

Response — 201 Created

```json
{ "data": { "gapId": 8001, "sttSessionId": 701, "eventKey": "stt-701-gap-0001", "reason": "ERROR", "startedAt": "2026-11-17T01:25:00Z", "endedAt": null } }
```

규칙: 브라우저 음성 인식 중단 시 기록, 기록이 없는 구간을 모두 오류로 간주하지 않음, `eventKey` 로 같은 이벤트 중복 저장 방지(동일 이벤트 재전송 시 기존 결과 반환, 같은 키로 다른 내용을 보내면 충돌 처리), 세션당 열린 누락 구간이 중복 생성되지 않도록 검증. 엔터티: `stt_gaps`

### [STT-04] STT 누락 구간 종료

**PATCH** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/gaps/{gapId}/close` — HOST, 강연방 소유자

Request `{ "endedAt": "2026-11-17T01:25:15Z" }`

```json
{ "data": { "gapId": 8001, "startedAt": "2026-11-17T01:25:00Z", "endedAt": "2026-11-17T01:25:15Z", "reason": "ERROR" } }
```

규칙: STT 가 정상 재시작된 시점에 종료, 종료 시각은 시작 시각보다 빠를 수 없음, 이미 닫힌 구간의 중복 처리 방지, 누락 구간은 회고 보고서에 별도 표시.

### [STT-01] STT 세션 종료

**POST** `/api/v1/rooms/{roomId}/stt/sessions/{sessionId}/stop` — HOST, 강연방 소유자

Request `{ "reason": "MANUAL_STOP" }`

```json
{ "data": { "sttSessionId": 701, "roomId": 10, "status": "STOPPED", "startedAt": "2026-11-17T01:00:02Z", "endedAt": "2026-11-17T02:00:00Z", "canRecord": false } }
```

규칙: 마지막 확정 전사 결과 저장 후 종료, `MANUAL_STOP`은 `STOPPED`, 복구 불가능한 오류인 `ERROR`는 `ERROR`로 저장한다. 중복 종료 시 최초 종료 상태와 시각을 반환한다, `reason` 은 ERD 에 저장 컬럼이 없는 참고 입력.

### [STT-05] 강연 발언 및 누락 구간 조회

**GET** `/api/v1/rooms/{roomId}/stt/segments?from=&to=` — HOST, 강연방 소유자

```json
{
  "data": {
    "roomId": 10,
    "segments": [
      {
        "segmentId": 9001,
        "sessionId": 701,
        "transcript": "Redis는 메모리 기반 데이터 저장소입니다.",
        "segmentStartAt": "2026-11-17T01:00:12Z",
        "segmentEndAt": "2026-11-17T01:00:19Z",
        "receivedAt": "2026-11-17T01:00:20Z"
      }
    ],
    "gaps": [
      { "gapId": 8001, "sessionId": 701, "reason": "ERROR", "startedAt": "2026-11-17T01:25:00Z", "endedAt": "2026-11-17T01:25:15Z" }
    ]
  }
}
```

규칙: 해당 강연의 모든 STT 세션 기록을 통합 조회하고 발언 시각 순으로 정렬, 오류·누락 구간은 별도 반환, 발언이 없는 구간을 AI 로 임의 보완하지 않음, 다른 강연방 STT 기록 조회 금지.

