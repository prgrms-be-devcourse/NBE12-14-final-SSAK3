# CS Match — REST·WebSocket API 명세서

> 기준: 사용자 제공 API 명세서(2026-11-12)를 레포용 Markdown으로 정리한 문서. URL·응답 형태·에러 코드 등 기존 계약은 유지한다. 정정된 값은 `docs/game-rules.md` 및 `docs/db/schema.sql`을 기준으로 맞췄다.
> DB 스키마: `docs/db/schema.sql` · 게임 정책: `docs/game-rules.md` · 협업: `docs/conventions.md`
> 미정인 구현 세부사항은 `CLAUDE.md`의 개발자 재량 원칙을 따른다. 다만 문서 사이의 실제 충돌은 임의 확정하지 않는다.


## 1. 공통 규칙

모든 REST API는 `/api/v1`을 기본 경로로 사용하고 JWT로 인증한다. 회원 인증, 프로필 조회, 매칭 신청·취소 등은 REST API를 사용하며, 매칭 성사 알림과 실시간 경기 진행은 WebSocket(`/ws`)을 사용한다. **[미정]** 표시는 아직 세부 구현 방식이 확정되지 않았음을 뜻한다. 확정된 게임 정책은 `docs/game-rules.md`를 따른다.

| 항목 | 규칙 |
| --- | --- |
| Base URL | `https://{도메인 미정}/api/v1` |
| 인증 | `Authorization: Bearer {accessToken}` (회원가입·로그인 제외) |
| 데이터 형식 | JSON, UTF-8, 필드명은 camelCase |
| 시간 형식 | REST는 ISO 8601 (예: `2026-11-06T12:00:00+09:00`), WebSocket은 서버 기준 epoch ms |
| 권한 | 일반 사용자 `USER`, 관리자 `ADMIN` (`/admin/**`는 ADMIN만 접근) |
| 페이지네이션 | `?page=0&size=20`, 응답은 `items`, `page`, `size`, `totalElements` |

성공 응답은 `data`에 담고, 본문이 없는 성공은 `204 No Content`를 쓴다.

```json
{ "data": { } }
```

실패 응답은 `error`에 코드와 메시지를 담는다.

```json
{ "error": { "code": "EMAIL_DUPLICATED", "message": "이미 가입된 이메일입니다." } }
```

| HTTP | 공통 에러 코드 | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 입력값 형식 오류 |
| 401 | `AUTH_REQUIRED` | 로그인 필요 또는 토큰 만료 |
| 403 | `FORBIDDEN` | 접근 권한 없음 |
| 404 | `NOT_FOUND` | 대상 없음 |
| 409 | `CONFLICT` | 현재 상태와 충돌 (세부 코드를 함께 사용) |
| 429 | `TOO_MANY_REQUESTS` | 요청 횟수 초과 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |

API로 노출하지 않고 서버 내부에서만 처리하는 것: 레이팅 계산, 문제 출제와 덱 생성, 정오답 판정, 라운드 제한 시간 판정, 무효 경기 판정, 대결 결과 저장.

### 공통 코드값

ERD의 컬럼 값과 API 값은 아래로 통일한다.

| 대상 | 값 |
| --- | --- |
| 난이도 `difficulty` | `EASY`(쉬움), `MEDIUM`(보통), `HARD`(어려움) |
| 경기 유형 `matchType` | `RATED`(레이팅), `UNRATED`(비레이팅) |
| 경기 상태 | `PREPARING`, `PLAYING`, `FINISHED`, `CANCELED` |
| 경기 결과 `result` | `WIN`, `LOSE`, `DRAW`, `FORFEIT` |
| 라운드 상태 | `READY`, `OPEN`, `EXPLANATION`, `FINISHED` |
| 제출 판정 `verdict` | `CORRECT`, `WRONG`, `LATE`, `REJECTED` |
| 문제 상태 | `DRAFT`(검수 대기), `ACTIVE`(출제 중), `HIDDEN`(숨김) |
| 문제 출처 `source` | `MANUAL`, `AI` |
| 신고 상태 | `OPEN`, `RESOLVED`, `REJECTED` |
| 회원 상태, 권한 | `ACTIVE` / `WITHDRAWN`, `USER` / `ADMIN` |

분야는 `categories` 테이블의 `categoryId`와 `name`을 쓰고, `GET /categories`(인증 필요)로 목록을 조회한다.

## 2. 회원·인증 API

회원가입과 로그인만 인증 없이 호출할 수 있고, 나머지는 모두 로그인 후 토큰이 필요하다. 이 문서는 이메일·비밀번호 로그인 방식을 정의한다. 소셜 로그인은 `docs/game-rules.md`의 범위 밖 기능이다.

| 기능 | Method | Path | 인증 |
| --- | --- | --- | --- |
| 회원가입 | POST | `/auth/signup` | 불필요 |
| 로그인 | POST | `/auth/login` | 불필요 |
| 로그아웃 | POST | `/auth/logout` | 필요 |
| 회원 탈퇴 | DELETE | `/users/me` | 필요 |
### 회원가입 `POST /auth/signup`

```jsonc
// 요청
{ "email": "user@example.com", "password": "********", "nickname": "코딩왕" }

// 응답 201
{ "data": { "userId": 1, "nickname": "코딩왕", "rating": 1000 } }
```

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 이메일 형식, 비밀번호 규칙, 닉네임 길이 위반 |
| 409 | `EMAIL_DUPLICATED` | 이미 가입된 이메일 |
| 409 | `NICKNAME_DUPLICATED` | 이미 사용 중인 닉네임 |
### 로그인 `POST /auth/login`

```jsonc
// 요청
{ "email": "user@example.com", "password": "********" }

// 응답 200
{ "data": { "accessToken": "eyJ...", "expiresIn": 3600 } }
```

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 401 | `INVALID_CREDENTIALS` | 이메일 또는 비밀번호 불일치 |
### 로그아웃 `POST /auth/logout`

응답은 `204 No Content`이다. 로그아웃 시 클라이언트는 저장된 인증 정보를 제거한다. 서버 측 Access Token의 즉시 무효화 여부, Refresh Token 발급 및 폐기 방식은 인증 구현 정책에 따른다.

### 회원 탈퇴 `DELETE /users/me`

응답 `204`. 계정 상태를 WITHDRAWN으로 바꾸고 탈퇴 시각을 기록한다. 이메일과 닉네임은 익명 값으로 바꿔 같은 이메일로 다시 가입할 수 있게 하고, 대결 이력은 남긴다 **[미정]**.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 409 | `MATCH_IN_PROGRESS` | 진행 중인 대결이 있어 탈퇴할 수 없음 |

## 3. 프로필·레이팅 API

프로필 화면과 로비에 필요한 내 정보, 닉네임 변경, 레이팅과 티어 조회를 제공한다. 레이팅 계산과 반영은 대결 종료 시 서버 내부에서만 하므로 변경 API는 없다.

| 기능 | Method | Path |
| --- | --- | --- |
| 내 프로필 조회 | GET | `/users/me` |
| 닉네임 변경 | PATCH | `/users/me/nickname` |
| 레이팅 티어 구간 조회 | GET | `/ratings/tiers` |
| 분야 목록 조회 | GET | `/categories` |
| 프로필 이미지 등록 및 변경 | PATCH | `/users/me/profile-image` |
| 프로필 이미지 삭제 | DELETE | `/users/me/profile-image` |
### 내 프로필 조회 `GET /users/me`

티어는 `rating_tiers` 구간에서 현재 레이팅이 속한 행으로 계산한다.

```jsonc
// 응답 200
{
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "코딩왕",
    "rating": 1016,
    "tier": { "tierId": 1, "tierName": "브론즈", "minRating": 0, "maxRating": 1200 },
    "createdAt": "2026-11-06T12:00:00+09:00",
    "profileImageUrl": "https://res.cloudinary.com/example/image/upload/profile.png"
  }
}
```


### 닉네임 변경 `PATCH /users/me/nickname`

```jsonc
// 요청
{ "nickname": "새닉네임" }

// 응답 200
{ "data": { "nickname": "새닉네임" } }
```

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 닉네임이 30자를 넘거나 문자 규칙 위반 |
| 409 | `NICKNAME_DUPLICATED` | 이미 사용 중인 닉네임 |
### 레이팅 티어 구간 조회 `GET /ratings/tiers`

프로필 화면의 티어 표를 그리는 데 쓴다. `rating_tiers` 테이블 값을 그대로 내려주며, 마스터는 `maxRating`이 `null`이다.

| `tierName` | `minRating` | `maxRating` |
| --- | --- | --- |
| 브론즈 | 0 | 1200 |
| 실버 | 1201 | 1400 |
| 골드 | 1401 | 1600 |
| 플래티넘 | 1601 | 1800 |
| 다이아 | 1801 | 2000 |
| 마스터 | 2001 | `null` |

```jsonc
// 응답 200
{
  "data": [
    { "tierId": 1, "tierName": "브론즈", "minRating": 0, "maxRating": 1200 },
    { "tierId": 6, "tierName": "마스터", "minRating": 2001, "maxRating": null }
  ]
}
```

### 분야 목록 조회 `GET /categories`

```jsonc
// 응답 200
{ "data": [ { "categoryId": 1, "name": "네트워크" }, { "categoryId": 2, "name": "운영체제" } ] }
```

초기 레이팅은 **1000**, Elo K값은 **32**로 확정되었다(`docs/game-rules.md`). 레이팅 하한 적용 방식은 개발 구현 정책에 따른다.

### 프로필 이미지 등록 및 변경

인증: 필요 (JWT)

Request

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `image` | File | O | 업로드할 프로필 이미지 |

```java
PATCH /api/v1/users/me/profile-image
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data

image: (이미지 파일)
```

Response — 200 OK

```java
{
  "data": {
    "profileImageUrl": "https://res.cloudinary.com/example/image/upload/profile.png"
  }
}
```

에러 코드

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 400 | `INVALID_IMAGE_FORMAT` | 지원하지 않는 파일 형식 |
| 400 | `IMAGE_SIZE_EXCEEDED` | 허용된 이미지 크기 초과 |
| 401 | `AUTH_REQUIRED` | 인증되지 않은 사용자 |
| 500 | `IMAGE_UPLOAD_FAILED` | Cloudinary 업로드 실패 |

처리 규칙

1. JWT로 로그인한 사용자를 확인한다.
2. 파일 형식과 크기를 검증한다.
3. Cloudinary에 이미지를 업로드한다.
4. 반환된 URL과 Public ID를 `users` 테이블에 저장한다.
5. 기존 이미지가 있다면 교체 과정에서 이전 이미지를 삭제한다.
### 프로필 이미지 삭제

인증: 필요 (JWT)

**Request**

요청 본문 없음.

```
DELETE /api/v1/users/me/profile-image
Authorization: Bearer {accessToken}
```

**Response — 204 No Content**

응답 본문 없음.

```
HTTP/1.1 204 No Content
```

에러 코드

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 401 | `AUTH_REQUIRED` | 인증되지 않은 사용자 |
| 404 | `PROFILE_IMAGE_NOT_FOUND` | 삭제할 프로필 이미지가 없음 |
| 500 | `IMAGE_DELETE_FAILED` | Cloudinary 이미지 삭제 실패 |

처리 규칙

1. JWT로 로그인한 사용자를 확인한다.
2. DB에서 `profile_image_public_id`를 조회한다.
3. Cloudinary에 저장된 이미지를 삭제한다.
4. `users.profile_image_url`, `users.profile_image_public_id`를 `NULL`로 변경한다.

## 4. 매칭 API

대기열 진입과 취소는 REST로, 매칭 성사 알림은 WebSocket(`MATCH_FOUND`)으로 받는다. 매칭 대기열은 DB 밖(Redis)에서 관리하고, 매칭이 성사되면 `matches`에 경기(`PREPARING`)를 만든다. 레이팅 매치는 레이팅이 가까운 상대를, 비레이팅 매치는 무작위 상대를 찾는다.

| 기능 | Method | Path |
| --- | --- | --- |
| 매칭 신청 | POST | `/matchmaking/queue` |
| 매칭 취소 | DELETE | `/matchmaking/queue` |
| 매칭 상태 조회 | GET | `/matchmaking/status` |
### 매칭 신청 `POST /matchmaking/queue`

```jsonc
// 요청
{ "matchType": "RATED" }

// 응답 202
{ "data": { "matchType": "RATED", "joinedAt": "2026-11-06T12:00:00+09:00" } }
```

`matchType`은 `RATED`(레이팅 매치) 또는 `UNRATED`(비레이팅 매치)다. 연습 모드는 상대가 없으므로 이 API를 쓰지 않고 6번 섹션의 API를 쓴다.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 409 | `ALREADY_IN_QUEUE` | 이미 대기열에 있음 |
| 409 | `MATCH_IN_PROGRESS` | 진행 중인 대결이 있음 |
### 매칭 취소 `DELETE /matchmaking/queue`

응답 `204`. 이미 매칭이 성사된 뒤에는 취소할 수 없다.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 404 | `NOT_IN_QUEUE` | 대기열에 없음 |
| 409 | `ALREADY_MATCHED` | 이미 매칭이 성사됨 |
### 매칭 상태 조회 `GET /matchmaking/status`

새로고침이나 재접속 뒤에 화면을 복구하는 데 쓴다.

```jsonc
// 응답 200
{
  "data": {
    "status": "WAITING",
    "matchType": "RATED",
    "waitedSeconds": 25,
    "ratingRange": 300,
    "matchId": null
  }
}
```

`status`는 `IDLE`(대기 안 함), `WAITING`(대기 중), `MATCHED`(성사, `matchId` 포함) 중 하나다.

WebSocket 연결 끊김 등으로 `MATCH_FOUND` 메시지를 수신하지 못한 경우, 클라이언트는 `GET /matchmaking/status`를 호출하여 매칭 상태를 확인한다. `status`가 `MATCHED`이면 응답에 포함된 `matchId`를 이용해 해당 경기 화면으로 이동한다.

### 탐색 규칙 (서버 내부)
- 레이팅 매치는 내 레이팅 기준 **±100점**에서 시작하고 **10초마다 ±100점**씩 넓히되 **±500점**에서 멈춘다(`docs/game-rules.md`).
    
- 대기 시간 상한은 없고, 대기 중에 연결이 끊기면 대기열에서 제거한다.
- 비레이팅 매치는 레이팅과 무관하게 대기 중인 아무 상대와 맺는다.
- 매칭이 성사되면 두 사람에게 WebSocket `MATCH_FOUND`를 보낸다.

## 5. 대결 WebSocket 메시지

로그인 직후 WebSocket을 한 번 연결해 두고, 매칭 성사 알림부터 대결 진행까지 같은 연결로 JSON 메시지를 주고받는다. 정답 판정과 라운드 시간 계산은 모두 서버가 하고, 클라이언트의 시계는 화면 표시용이다.

### 연결과 메시지 형식

| 항목 | 규칙 |
| --- | --- |
| 주소 | `wss://{도메인 미정}/ws?token={accessToken}` (인증 방식은 **[미정]**, 대안은 연결 직후 `AUTH` 메시지) |
| 메시지 형식 | `{ "type": "...", "payload": { } }` |
| 시각 | 서버 기준 epoch ms (`endsAt` 등), 남은 시간은 클라이언트가 서버 시각 기준으로 계산 |
| 하트비트 | 클라이언트가 5초마다 `PING`, 서버는 15초 동안 신호가 없으면 연결 끊김으로 판단 **[미정]** |

### 경기 규칙 (ERD 기준)

| 항목 | 값 |
| --- | --- |
| 총 라운드 | 10 (`matches.total_rounds`) |
| 라운드 제한 시간 | 20초 (`matches.round_time_limit_sec`) |
| 정답 점수 | `EASY` +20, `MEDIUM` +30, `HARD` +40. 출제 시점의 값이 `match_rounds.correct_score`에 저장된다 |
| 오답 감점 | `EASY` −10, `MEDIUM` −15, `HARD` −20 (`wrong_score_delta`). 제출마다 감점하고 점수는 음수가 될 수 있다 |
| 오답 후 | 오답 판정 시 난이도에 따른 점수를 감점하고, 판정이 확정된 시각부터 3초간 신규 답안 제출을 제한한다. 3초가 지나고 라운드가 아직 진행 중이면 다시 제출할 수 있다. |
| 득점 | 라운드에서 가장 먼저 처리된 정답 1건만 득점(`CORRECT`)하고, 이후 정답은 `LATE`로 점수가 없다 |
| 처리 순서 | 서버가 라운드 안에서 처리 순번(`received_sequence`)을 매기고 그 순서로 판정한다 |
| 라운드 종료 | 정답이 처리되면 즉시 종료하고, 아니면 마감 시각(`deadline_at`)에 종료한다 |
| 중복 요청 | 같은 `requestId`로 다시 보내면 새 제출로 치지 않고 이전 결과를 그대로 응답한다 |
### 클라이언트 → 서버


| type | payload | 설명 |
| --- | --- | --- |
| `PING` | 없음 | 연결 유지 확인, 서버는 `PONG`으로 응답 |
| `READY` | `matchId` | 경기 화면 준비 완료(`ready_at` 기록). 둘 다 보내면 서버가 `MATCH_START`를 보낸다 |
| `ANSWER` | `matchId`, `roundNo`, `choiceId`, `requestId` | 보기 제출. `requestId`는 요청마다 만든 UUID이고, 재전송할 때는 같은 값을 쓴다 |
| `FORFEIT` | `matchId` | 기권, 즉시 패배 처리 |
| `RESUME` | `matchId` | 재접속 후 진행 중인 대결 상태 요청 |

`READY`를 한쪽만 보냈을 때 대기 시간 및 처리 정책 `[미정]`

### 서버 → 클라이언트


| type | payload | 설명 |
| --- | --- | --- |
| `PONG` | 없음 | `PING` 응답 |
| `MATCH_FOUND` | `matchId`, `matchType`, `opponent`(`nickname`, `rating`, `tierName`) | 매칭 성사. 경기 상태는 `PREPARING` |
| `MATCH_START` | `matchId`, `startsAt`, `totalRounds`, `roundTimeLimitSec` | 둘 다 준비되면 전송. `startsAt`까지 카운트다운 후 시작하고 상태는 `PLAYING` |
| `ROUND_START` | `matchId`, `roundNo`, `question`, `choices`, `correctScore`, `wrongScoreDelta`, `endsAt`, `scores` ,`startsAt` | 새 라운드(상태 `OPEN`). 두 사람에게 같은 문제를 동시에 보냄 |
| `ANSWER_RESULT` | `roundNo`, `requestId`, `verdict`, `scoreDelta`, `scores`, `reason`, `retryAvailableAt` | 내 제출의 처리 결과 |
| `SCORE_UPDATED` | `matchId`, `roundNo`, `scores` | 정답 또는 오답으로 점수가 변경되면 두 참가자에게 최신 점수를 전달 |
| `ROUND_END` | `roundNo`, `winner`, `correctChoiceId`, `explanation`, `scores`, `nextRoundAt` | 라운드 종료(상태 `EXPLANATION`). 정답과 해설을 이때 공개 |
| `OPPONENT_STATUS` | `connected` | 상대 연결 끊김 또는 복구 알림 |
| `STATE_SNAPSHOT` | `matchId`, `matchStatus` 및 현재 상태(`OPEN`/`EXPLANATION`/`FINISHED`)에 따른 필드 | `RESUME` 응답. 재접속한 사용자의 경기 화면 복원 |
| `MATCH_END` | `matchId`, `result`, `finalScores`, `ratingChange`, `endReason` | 경기 종료와 결과 확정 |
| `ERROR` | `code`, `message` | 잘못된 요청 |
#### `STATE_SNAPSHOT` 상태별 응답

`STATE_SNAPSHOT`은 경기 상태에 따라 필드가 달라진다. `OPEN`에서는 현재 문제와 보기를 제공하되 정답은 공개하지 않는다. `EXPLANATION`에서는 정답과 해설, `FINISHED`에서는 최종 경기 결과를 제공한다. `serverTime`, `retryAvailableAt` 등은 해당 상태 및 구현에 필요한 경우 포함한다. 아래 상태별 예시를 따른다.

#### `SCORE_UPDATED` 전송 규칙

- `ANSWER_RESULT`: 제출한 사용자에게만 판정 및 점수 변동 결과 전달.
- `SCORE_UPDATED`: 점수가 변할 때 두 참가자에게 최신 점수 전달. `scores.me`와 `scores.opponent`는 메시지 수신자를 기준으로 작성한다.
- `ROUND_END`: 정답·해설과 최종 라운드 점수 공개.

### 제출 판정 `verdict`


| 값 | 의미 | 반영 점수 |
| --- | --- | --- |
| `CORRECT` | 라운드에서 가장 먼저 처리된 정답 | `+correctScore` |
| `WRONG` | 오답 (제출마다 감점) | `wrongScoreDelta` |
| `LATE` | 유효하게 접수된 정답이지만, 다른 사용자의 정답이 먼저 확정되어 점수를 받지 못한 경우 | 0 |
| `REJECTED` | 무효 요청. `reason`은 `ROUND_NOT_OPEN`, `INVALID_CHOICE`, `NOT_PARTICIPANT`, `MATCH_ENDED` ,`ANSWER_COOLDOWN`중 하나

`ANSWER_COOLDOWN` = 오답 후 3초가 지나지 않아서 거부됨 | 0 |
### 주요 메시지 예시

```jsonc
// ANSWER (클라이언트 → 서버)
{ "type": "ANSWER", "payload": { "matchId": 101, "roundNo": 3, "choiceId": 5521, "requestId": "3f1c9a52-8d3e-4b7a-9c11-0a6f2d4e7b10" } }

// ROUND_START
{
  "type": "ROUND_START",
  "payload": {
    "matchId": 101,
    "roundNo": 3,
    "question": { "questionId": 55, "categoryId": 3, "difficulty": "MEDIUM", "content": "교착 상태 발생의 4가지 필요 조건에 해당하지 않는 것은?" },
    "choices": [ { "choiceId": 5521, "choiceNo": 1, "content": "상호 배제" }, { "choiceId": 5523, "choiceNo": 2, "content": "선점" } ],
    "correctScore": 30,
    "wrongScoreDelta": -15,
    "startsAt": 1793945380000,
    "endsAt": 1793945400000,
    "scores": { "me": 20, "opponent": 10 }
  }
}

// ANSWER_RESULT (오답, 같은 라운드에서 재제출 가능)
{ 
	"type": "ANSWER_RESULT", 
		"payload": { 
			"roundNo": 3, 
			"requestId": "3f1c9a52-8d3e-4b7a-9c11-0a6f2d4e7b10",
			"verdict": "WRONG", 
			"scoreDelta": -15, 
			"scores": { "me": 5, "opponent": 10 }, 
			"reason": null,
			"retryAvailableAt": 1793945398000
		} 
}

// ROUND_END
{
  "type": "ROUND_END",
  "payload": {
    "roundNo": 3,
    "winner": "OPPONENT",
    "correctChoiceId": 5523,
    "explanation": "필요 조건은 상호 배제, 점유와 대기, 비선점, 순환 대기다.",
    "scores": { "me": 5, "opponent": 40 },
    "nextRoundAt": 1793945405000
  }
}

// MATCH_END
{
  "type": "MATCH_END",
  "payload": {
    "matchId": 101,
    "result": "WIN",
    "finalScores": { "me": 180, "opponent": 120 },
    "ratingChange": { "before": 1000, "after": 1016, "delta": 16 },
    "endReason": "NORMAL"
  }
}

// SCORE_UPDATED (서버 → 클라이언트)
{
  "type": "SCORE_UPDATED",
  "payload": {
    "matchId": 101,
    "roundNo": 3,
    "scores": {
      "me": 5,
      "opponent": 10
    }
  }
}
//문제 풀이 중 — PLAYING / OPEN
{
  "type": "STATE_SNAPSHOT",
  "payload": {
    "matchId": 101,
    "matchStatus": "PLAYING",
    "roundStatus": "OPEN",
    "roundNo": 3,
    "question": {"questionId": 55, "content": "문제 내용"},
    "choices": [],
    "endsAt": 1793945400000,
    "scores": {"me": 20, "opponent": 10}
  }
}
//해설 확인 중 — PLAYING / EXPLANATION
{
  "type": "STATE_SNAPSHOT",
  "payload": {
    "matchId": 101,
    "matchStatus": "PLAYING",
    "roundStatus": "EXPLANATION",
    "roundNo": 3,
    "correctChoiceId": 5523,
    "explanation": "정답 해설",
    "nextRoundAt": 1793945405000,
    "scores": {"me": 5, "opponent": 40}
  }
}
//③ 경기 종료 — FINISHED
{
  "type": "STATE_SNAPSHOT",
  "payload": {
    "matchId": 101,
    "matchStatus": "FINISHED",
    "result": "WIN",
    "finalScores": {"me": 180, "opponent": 120},
    "ratingChange": {
      "before": 1000,
      "after": 1016,
      "delta": 16
    },
    "endReason": "NORMAL"
  }
}
//경기 무효 - Cancel [미정]
```

`winner`는 `ME`, `OPPONENT`, `NONE`(아무도 못 맞힘) 중 하나다. `result`는 ERD 값인 `WIN`, `LOSE`, `DRAW`, `FORFEIT`(기권하거나 이탈해 진 쪽)이다. 무효 경기를 나타내는 값은 ERD에 없어 `VOID`로 적어 두었고 **[미정]** 이다(`matches.status`의 `CANCELED`를 쓸지 결정 필요). 비레이팅 매치와 무효 경기의 `ratingChange`는 `null`이다. `endReason`(`NORMAL`, `FORFEIT`, `DISCONNECT`, `BOTH_DISCONNECTED`)은 저장 컬럼이 없어 메시지에서만 쓰며 **[미정]** 이다.

### 인증 및 참가자 검증
- WebSocket 연결 시 사용자의 JWT 인증을 수행한다.
- `READY`, `ANSWER`, `FORFEIT`, `RESUME` 요청을 처리할 때 인증된 사용자가 해당 경기의 참가자인지 검증한다.
- `ANSWER` 요청에서는 제출한 `choiceId`가 현재 라운드에 출제된 문제의 보기인지 검증한다.
- 인증되지 않은 사용자나 해당 경기의 참가자가 아닌 사용자의 요청은 처리하지 않는다.
- 검증에 실패한 요청은 기존 `ERROR` 메시지 또는 정의된 제출 거절 응답으로 처리한다.

### 한 라운드의 흐름

1. 서버가 두 플레이어에게 `ROUND_START`를 동시에 보낸다. 정답 정보는 포함하지 않는다.
2. 플레이어가 `ANSWER`를 보내면 서버가 처리 순번을 매겨 순서대로 판정하고 제출자에게 `ANSWER_RESULT`를 보낸다.
3. 정답 또는 오답으로 점수가 변경되면 서버는 양쪽 참가자에게 `SCORE_UPDATED`를 전송한다. 오답인 경우 3초 쿨다운 이후 라운드가 계속 열려 있으면 다시 제출할 수 있다.
4. 정답(`CORRECT`)이 처리되는 즉시 라운드가 끝난다. 아무도 못 맞히면 `endsAt`에 점수 변동 없이 끝난다.
5. 서버가 두 사람에게 `ROUND_END`(정답, 해설, 점수)를 보내고, `nextRoundAt`에 다음 `ROUND_START`를 보낸다.
6. 10라운드가 끝나면 `MATCH_END`를 보내고 점수로 승패를 확정해 레이팅을 반영한다.

### 판정 규칙

| 상황 | 처리 |
| --- | --- |
| 동시 제출 | 서버 처리 순번 순서로 판정. 먼저 처리된 정답 1건만 `CORRECT`, 나머지 정답은 `LATE` |
| 오답 | 감점 후 재제출 가능. 연타로 점수가 크게 깎이는 것을 막는 제한(최소 제출 간격 등)은 오답 후 3초 쿨다운 |
| 중복 제출, 라운드·경기 종료 후 제출 | 같은 `requestId`는 이전 결과를 다시 응답, 종료 후 제출은 `REJECTED` |
| 연결 끊김 | 라운드는 계속 진행되고 끊긴 사람은 점수를 얻지 못함. 재접속하면 `RESUME`으로 복구 |
| 둘 다 연결 끊김 | 경기를 무효 처리하고 레이팅 변동 없음 |
| 기권 | 즉시 패배(`FORFEIT`), 레이팅 매치면 레이팅 반영 |
| 동점 | `DRAW`. Elo에서 무승부 결과값(0.5)으로 계산한다(`docs/game-rules.md`). |

하트비트 주기와 재접속 유예 시간(이탈 패배 기준)은 **[미정]** 이다.

## 6. 연습 모드 API

연습은 JWT 인증된 회원이 REST로 진행한다. 전체 또는 선택 분야의 미풀이 `ACTIVE` 문제를 무작위로 **최대 10개씩** 제공한다. 마지막 묶음은 남은 수만큼이며 시간제한은 없다. 분야 간 진도는 공유하고 답안을 제출하면 정답 여부와 무관하게 푼 문제로 계산한다. 규칙은 `docs/game-rules.md`의 연습 절을 따른다.

| 기능 | Method | Path |
| --- | --- | --- |
| 연습 시작 | POST | `/practice/sessions` |
| 다음 문제 받기 | GET | `/practice/sessions/{sessionId}/question` |
| 답안 제출 | POST | `/practice/sessions/{sessionId}/answers` |
| 연습 결과 조회 | GET | `/practice/sessions/{sessionId}` |
| 진행 중 연습 조회 | GET | `/practice/sessions/current` |
| 중도 종료 | POST | `/practice/sessions/{sessionId}/end` |
| 연습 이력 목록 | GET | `/practice/sessions?page=0&size=20` |
| 문제별 이력 | GET | `/practice/sessions/{sessionId}/questions?wrongOnly=false` |
| 전체/분야 진도 | GET | `/practice/progress?categoryId=1` |
| 전체 진도 초기화 | POST | `/practice/restart` |
| 분야 목록 | GET | `/practice/categories` |

모든 경로는 `/api/v1` 아래에 있다. 다른 회원의 세션 조회·제출·종료는 `403 FORBIDDEN`, 없는 세션·분야는 `404 NOT_FOUND`다. 목록의 `page`는 0 이상, `size`는 1~100이다.
### 연습 시작 `POST /practice/sessions`

```jsonc
// 요청 (categoryId는 선택, 없으면 전체 분야)
{ "categoryId": 1 }

// 응답 201
{ "data": { "sessionId": 7, "categoryId": 1, "learningCycle": 1, "questionCount": 10,
  "answeredCount": 0, "correctCount": 0, "status": "IN_PROGRESS",
  "startedAt": "2026-11-12T12:00:00+09:00", "endedAt": null } }
```

회원당 진행 중인 연습은 하나다. 같은 분야로 다시 시작을 요청하면 기존 연습을 `200`으로 반환하며, 다른 분야라면 `409 PRACTICE_IN_PROGRESS`를 반환한다. 새 연습은 `201`이다. 출제할 미풀이 문제가 없으면 `404 NO_MORE_QUESTIONS`다. 새 문제가 활성화되면 다음 묶음부터 포함한다. `timeLimitSec` 필드는 제거했다.

### 다음 문제 받기 `GET /practice/sessions/{sessionId}/question`

```jsonc
// 응답 200
{
  "data": {
    "index": 4,
    "total": 10,
    "question": { "questionId": 12, "categoryId": 1, "difficulty": "EASY", "content": "HTTP의 기본 포트 번호는?" },
    "choices": [ { "choiceId": 101, "choiceNo": 1, "content": "80" }, { "choiceId": 102, "choiceNo": 2, "content": "443" } ]
  }
}
```

GET을 반복하거나 새로고침해도 제출 전에는 같은 문제를 반환한다. `index`는 숨김 건너뛰기를 제외한 현재 풀이 순번, `total`은 묶음의 실제 문제 수다. 종료한 연습 또는 남은 문제가 없으면 `404 NO_MORE_QUESTIONS`를 반환한다. 정답·해설·보기의 정답 여부는 포함하지 않는다.

보기는 연습 생성 시 한 번 섞어 저장한다. `choiceNo`는 섞인 후의 연습 표시 순서(1~4), `choiceId`는 원본 보기 ID이며 답안 제출은 `choiceId`로 한다. 재조회·이어 풀기·이력에서도 같은 순서를 반환한다. 기존에 생성된 연습의 저장된 순서는 변경하지 않는다. 난이도는 `EASY`, `MEDIUM`, `HARD`다.

### 답안 제출 `POST /practice/sessions/{sessionId}/answers`

상대가 없으므로 제출 즉시 정답과 해설을 돌려준다.

```jsonc
// 요청
{ "questionId": 12, "choiceId": 101 }

// 응답 200
{
  "data": {
    "correct": true,
    "correctChoiceId": 101,
    "explanation": "HTTP는 80, HTTPS는 443번을 기본으로 사용한다."
  }
}
```

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 409 | `ALREADY_ANSWERED` | 이미 제출한 문제 |
| 409 | `SESSION_FINISHED` | 이미 끝난 연습 |
| 409 | `QUESTION_UNAVAILABLE` | 현재 묶음에서 숨김 처리되어 건너뛴 문제 |
| 400 | `VALIDATION_FAILED` | 현재 문제 이외의 문제, 다른 문제의 보기, 잘못된 ID |

같은 회원의 요청은 직렬 처리한다. 중복 요청은 점수·진도를 다시 반영하지 않으며, 완료한 세션은 `SESSION_FINISHED`가 우선한다. 마지막 제출로 자동 완료된다. 응답 유실 후에는 문제별 이력에서 제출 결과와 해설을 다시 조회할 수 있다.
### 연습 결과 조회 `GET /practice/sessions/{sessionId}`

```jsonc
// 응답 200
{
  "data": {
    "sessionId": 7,
    "categoryId": 1,
    "learningCycle": 1,
    "questionCount": 10,
    "answeredCount": 10,
    "correctCount": 8,
    "status": "COMPLETED",
    "startedAt": "2026-11-06T12:00:00+09:00",
    "endedAt": "2026-11-06T12:04:00+09:00"
  }
}
```

`status`는 `IN_PROGRESS`, `COMPLETED`, `ABANDONED`다. 시작·종료 시각은 `+09:00`으로 제공한다. 중도 종료는 미제출 문제를 오답으로 세지 않는다. 숨긴 미제출 문제는 건너뛰고 `questionCount`를 줄이며, 남은 문제가 없어지면 완료한다. 레이팅은 변경하지 않는다.

### 이어 풀기와 중도 종료

`GET /practice/sessions/current`는 위 세션 요약을 반환한다. 진행 중 연습이 없으면 `200 { "data": null }`이다. 여러 탭은 같은 서버 진도를 조회한다. WebSocket 푸시는 사용하지 않으므로 화면 갱신 시 다시 조회한다.

`POST /practice/sessions/{sessionId}/end`는 미완료 연습을 `ABANDONED`로 종료하고 세션 요약을 `200`으로 반환한다. 이미 종료된 연습은 기존 요약을 그대로 반환한다. 미제출 문제는 다음 연습에 다시 배정될 수 있다.

### 연습 이력

`GET /practice/sessions`는 시작 시각 내림차순(동률은 ID 내림차순)으로 `{data: {items: [세션 요약], page, size, totalElements}}`를 반환한다. 현재 진행 중인 연습도 포함한다.

`GET /practice/sessions/{sessionId}/questions`는 다음 항목의 배열을 `data`로 반환한다. `wrongOnly=true`면 제출한 오답만 반환한다. 기본값은 전체이며 미제출·건너뛴 문제도 포함한다.

```jsonc
{ "data": [{
  "index": 1,
  "question": { "questionId": 12, "categoryId": 1, "difficulty": "EASY", "content": "출제 당시 본문" },
  "choices": [{ "choiceId": 101, "choiceNo": 1, "content": "출제 당시 보기" }],
  "status": "ANSWERED", "hidden": false,
  "selectedChoiceId": 101, "correct": true, "correctChoiceId": 101,
  "explanation": "출제 당시 해설", "submittedAt": "2026-11-12T12:01:00+09:00"
}] }
```

문제별 `status`는 `ANSWERED`, `UNANSWERED`, `SKIPPED`다. 이력의 `index`는 최초 배정 순번이다. 미제출 문제의 `selectedChoiceId`, `correct`, `correctChoiceId`, `explanation`, `submittedAt`은 `null`이다. 이미 제출한 문제는 현재 숨김 여부와 무관하게 당시 내용을 보존한다. 원본 문제와 보기가 수정돼도 출제 당시 사본으로 채점·조회한다.

### 진도와 처음부터 다시 풀기

`GET /practice/progress`는 전체 진도, `categoryId`를 지정하면 해당 분야 진도다.

```jsonc
{ "data": { "learningCycle": 1, "totalCount": 14, "answeredCount": 10,
  "remainingCount": 4, "completed": false, "currentSessionId": null } }
```

진도는 현재 `ACTIVE` 문제 기준이며, 오답도 `answeredCount`에 포함한다. 대상이 0개이면 `completed`는 `false`다. 신규 문제가 활성화되면 완료 상태에서도 남은 문제 수가 늘어난다. `currentSessionId`는 분야 필터와 무관하게 회원의 진행 중 연습 ID다.

`POST /practice/restart`는 전체 분야의 학습 회차를 1 증가시키고 위 전체 진도를 반환한다. 기존 이력과 복습 목록은 보존한다. 진행 중 연습이 있으면 `409 PRACTICE_IN_PROGRESS`다. 이 요청을 재호출하면 새로운 회차로 다시 초기화한다.

`GET /practice/categories`는 `{data: [{categoryId: 1, name: "네트워크"}]}`를 반환한다.

영구 저장은 `practice_progress`, `practice_sessions`, `practice_answers`, `practice_choices`를 사용한다. 기존 DB 적용은 `docs/db/migrations/README.md`를 따른다.


## 7. 결과·복습 API

경기 결과 화면과 복습 목록에 필요한 API다. 결과는 대결 종료 때 `MATCH_END`로도 오지만, 새로고침이나 나중에 다시 볼 때를 위해 REST로도 조회할 수 있다. 복습 목록은 `saved_questions`(회원과 문제가 기본키)를 따라 문제 ID로 항목을 구분한다.

| 기능 | Method | Path |
| --- | --- | --- |
| 경기 결과 조회 | GET | `/matches/{matchId}/result` |
| 내가 틀린 문제 조회 | GET | `/matches/{matchId}/wrong-questions` |
| 복습 문제 저장 | POST | `/review-questions` |
| 복습 문제 목록 조회 | GET | `/review-questions` |
| 복습 문제 삭제 | DELETE | `/review-questions/{questionId}` |
### 경기 결과 조회 `GET /matches/{matchId}/result`

본인이 참가한 경기만 조회할 수 있다. `result`와 점수, 레이팅 전후 값은 `match_players`에서 가져온다.

```jsonc
// 응답 200
{
  "data": {
    "matchId": 101,
    "matchType": "RATED",
    "result": "WIN",
    "finalScores": { "me": 180, "opponent": 120 },
    "opponent": { "nickname": "상대닉", "tierName": "브론즈" },
    "ratingChange": { "before": 1000, "after": 1016, "delta": 16 },
    "endedAt": "2026-11-06T12:05:00+09:00"
  }
}
```

비레이팅 매치와 무효 경기는 `ratingChange`가 `null`이다. 무효 경기를 어떻게 표시할지는 ERD에 값이 없어 **[미정]** 이다.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 403 | `FORBIDDEN` | 내가 참가하지 않은 경기 |
| 404 | `MATCH_NOT_FOUND` | 없는 경기 |
| 409 | `MATCH_NOT_ENDED` | 아직 진행 중인 경기 |
### 내가 틀린 문제 조회 `GET /matches/{matchId}/wrong-questions`

내가 `WRONG` 판정을 받은 문제(`round_answers`)를 기본으로 한다.

```jsonc
// 응답 200
{
  "data": [
    {
      "questionId": 55,
      "categoryId": 1,
      "difficulty": "MEDIUM",
      "content": "HTTP 상태 코드 401과 403의 차이로 옳은 것은?",
      "myChoiceContent": "401은 서버 오류이고 403은 클라이언트 오류이다",
      "correctChoiceContent": "401은 인증되지 않음, 403은 인증은 됐지만 권한 없음",
      "explanation": "401은 인증 실패, 403은 인증 후 권한 없음이다.",
      "saved": false
    }
  ]
}
```

시간 안에 아무도 못 맞힌 문제를 포함할지는 **[미정]** 이다. `saved`는 이미 복습 목록에 저장했는지 나타낸다.

### 복습 문제 저장 `POST /review-questions`

```jsonc
// 요청
{ "matchId": 101, "questionId": 55 }

// 연습에서 저장할 때 (matchId와 practiceSessionId 중 정확히 하나)
{ "practiceSessionId": 7, "questionId": 55 }

// 응답 201
{ "data": { "questionId": 55, "savedAt": "2026-11-06T12:06:00+09:00" } }
```

`saved_questions`에는 문제 사본이 아니라 문제 ID와 출처를 저장한다. 본인이 참가한 경기의 출제 문제 또는 본인 연습에서 제출한 문제만 저장할 수 있다. 출처를 둘 다 지정하거나 생략하면 `400 VALIDATION_FAILED`다. 연습의 미제출 문제는 `403 FORBIDDEN`, 현재 비활성 문제는 `409 QUESTION_UNAVAILABLE`다.

목록은 최신 내용을 반환한다. 숨긴 문제는 제외하지 않고 `available: false`로 표시하며 `content`, `correctChoiceId`, `correctChoiceContent`, `explanation`은 `null`, `choices`는 빈 배열로 반환한다. 회원·문제별로 하나만 저장되므로 다른 연습이나 경기에서 다시 저장해도 `ALREADY_SAVED`다.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 409 | `ALREADY_SAVED` | 이미 저장한 문제 |
| 403 | `FORBIDDEN` | 내 경기가 아니거나 그 경기에 출제되지 않은 문제 |
### 복습 문제 목록 조회 `GET /review-questions`

쿼리: `page`, `size`, `categoryId`(선택). 프로필·복습 화면에서 쓴다.

```jsonc
// 응답 200
{
  "data": {
    "items": [
      {
        "questionId": 55,
        "categoryId": 1,
        "difficulty": "MEDIUM",
        "available": true,
        "content": "HTTP 상태 코드 401과 403의 차이로 옳은 것은?",
        "choices": [{ "choiceId": 101, "choiceNo": 1, "content": "401은 인증되지 않음, 403은 인증은 됐지만 권한 없음" }],
        "correctChoiceId": 101,
        "correctChoiceContent": "401은 인증되지 않음, 403은 인증은 됐지만 권한 없음",
        "explanation": "401은 인증 실패, 403은 인증 후 권한 없음이다.",
        "savedAt": "2026-11-06T12:06:00+09:00"
      }
    ],
    "page": 0, "size": 20, "totalElements": 1
  }
}
```

### 복습 문제 삭제 `DELETE /review-questions/{questionId}`

응답 `204`. 최신 기능 목록에는 없는 기능이라 **[미정]** 이다. 와이어프레임에는 "삭제 [기능 미정]" 버튼으로만 넣었다.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 404 | `NOT_FOUND` | 저장하지 않은 문제 |

## 8. 문제 오류 신고 API

사용자는 라운드 종료 화면이나 경기 결과 화면에서 문제를 신고한다. 신고에 `matchId`를 함께 받아 두어야 관리자가 잘못된 문제로 진행된 경기를 찾아 레이팅을 되돌릴 수 있다. `question_reports`의 `reason`은 500자 이하 자유 입력 한 칸이다.

| 기능 | Method | Path |
| --- | --- | --- |
| 문제 오류 신고 | POST | `/questions/{questionId}/reports` |
### 문제 오류 신고 `/questions/{questionId}/reports`

```jsonc
// 요청
{
  "matchId": 101,
  "reason": "정답이 틀린 것 같아요"
}

// 응답 201
{ "data": { "reportId": 31, "status": "OPEN" } }
```

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `reason`이 비어 있거나 500자를 넘음 |
| 403 | `FORBIDDEN` | 내가 참가하지 않은 경기이거나 그 경기에 출제되지 않은 문제 |
| 409 | `ALREADY_REPORTED` | 같은 경기에서 같은 문제를 이미 신고함 |

**신고 관련 설계 참고**

- 신고 사유를 코드(정답 오류, 보기 모호 등)로 나누려면 `question_reports`에 사유 코드 컬럼이 필요하다. 지금은 자유 입력만 가능하다.
- `ALREADY_REPORTED`를 위한 (`question_id`, `reporter_id`, `match_id`) UNIQUE 제약은 이미 ERD 및 `schema.sql`에 존재한다.
- 신고가 N건 쌓이면 문제를 자동으로 숨김(`HIDDEN`) 처리할지(기준 N건)도 정해야 한다.

## 9. 관리자 API

`/admin/**`은 `ADMIN` 권한만 호출할 수 있다. 문제 직접 관리, AI 생성 문제 검수, 신고 처리, 잘못된 문제로 진행된 경기의 레이팅 롤백을 다룬다. 문제 상태는 ERD에 맞춰 `DRAFT`(검수 대기), `ACTIVE`(출제 중), `HIDDEN`(숨김, 비활성화와 반려 모두)로 관리한다.

| 기능 | Method | Path |
| --- | --- | --- |
| 문제 목록 조회 | GET | `/admin/questions` |
| 문제 상세 조회 | GET | `/admin/questions/{questionId}` |
| 문제 등록 | POST | `/admin/questions` |
| 문제 수정 | PUT | `/admin/questions/{questionId}` |
| 문제 상태 변경 (승인·반려·활성화·비활성화) | PATCH | `/admin/questions/{questionId}/status` |
| 신고 목록 조회 | GET | `/admin/reports` |
| 신고 상세 조회 | GET | `/admin/reports/{reportId}` |
| 신고 처리 | PATCH | `/admin/reports/{reportId}/status` |
| 경기 레이팅 롤백 | POST | `/admin/matches/{matchId}/rating-rollback` |
### 문제 목록 조회 `GET /admin/questions`

쿼리: `status`, `categoryId`, `difficulty`, `source`, `page`, `size`. AI 생성 문제의 검수 대기 목록은 `status=DRAFT&source=AI`로 조회한다.

```jsonc
// 응답 200
{
  "data": {
    "items": [
      { "questionId": 102, "categoryId": 5, "difficulty": "HARD", "content": "복합 인덱스 (a, b, c)가 있을 때...", "status": "ACTIVE", "source": "AI", "reportCount": 1 }
    ],
    "page": 0, "size": 20, "totalElements": 1
  }
}
```

`reportCount`는 해당 문제의 신고 수를 계산해 내려주는 값이며 컬럼은 없다.

### 문제 상세 조회 `GET /admin/questions/{questionId}`

```jsonc
// 응답 200
{
  "data": {
    "questionId": 103,
    "categoryId": 3,
    "difficulty": "MEDIUM",
    "content": "교착 상태 발생의 4가지 필요 조건에 해당하지 않는 것은?",
    "choices": [
      { "choiceId": 1, "choiceNo": 1, "content": "선점", "isCorrect": true },
      { "choiceId": 2, "choiceNo": 2, "content": "순환 대기", "isCorrect": false }
    ],
    "explanation": "...",
    "status": "DRAFT",
    "source": "AI",
    "createdBy": null,
    "createdAt": "2026-11-06T12:00:00+09:00"
  }
}
```

AI 생성 문제의 근거 개념은 이미 `concepts`와 `questions.concept_id`로 연결되어 있다. 관리자 상세 응답에 개념 정보를 노출할지는 구현 정책에 따른다.

### 문제 등록/수정 `POST /admin/questions`, `PUT /admin/questions/{questionId}`

```jsonc
// 요청
{
  "categoryId": 1,
  "difficulty": "EASY",
  "content": "HTTP 상태 코드 404가 의미하는 것은?",
  "choices": [
    { "choiceNo": 1, "content": "요청한 리소스를 찾을 수 없음", "isCorrect": true },
    { "choiceNo": 2, "content": "서버 내부 오류", "isCorrect": false },
    { "choiceNo": 3, "content": "인증이 필요함", "isCorrect": false },
    { "choiceNo": 4, "content": "요청이 성공함", "isCorrect": false }
  ],
  "explanation": "404 Not Found는 요청한 리소스를 찾지 못했을 때 반환한다."
}

// 응답 201 (수정은 200)
{ "data": { "questionId": 104, "status": "DRAFT" } }
```

수동 등록 문제도 ERD 기본값이 `DRAFT`라서 승인(상태 변경)을 거쳐야 출제된다. 관리자가 등록할 때 바로 `ACTIVE`를 허용할지는 **[미정]** 이다.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 보기가 4개가 아니거나 정답이 정확히 1개가 아님, 필수 값 없음 |
| 404 | `NOT_FOUND` | 없는 문제 |
| 409 | `QUESTION_IN_USE` | 경기에 한 번이라도 출제된 문제는 수정할 수 없음. 숨김 처리 후 새로 등록 |
### 문제 상태 변경 `PATCH /admin/questions/{questionId}/status`

```jsonc
// 요청
{ "status": "ACTIVE" }

// 응답 200
{ "data": { "questionId": 103, "status": "ACTIVE" } }
```

| 동작 | 상태 변화 |
| --- | --- |
| 검수 승인 | `DRAFT` → `ACTIVE` (출제 풀에 들어감) |
| 검수 반려 | `DRAFT` → `HIDDEN` (반려 사유를 저장할 컬럼이 ERD에 없어 **[미정]**) |
| 비활성화 | `ACTIVE` → `HIDDEN` |
| 다시 활성화 | `HIDDEN` → `ACTIVE` |

`DRAFT`로 되돌리는 요청은 `400 VALIDATION_FAILED`를 반환한다.

### 신고 목록·상세 `GET /admin/reports`, `GET /admin/reports/{reportId}`

목록 쿼리: `status`(`OPEN`, `RESOLVED`, `REJECTED`), `page`, `size`. 상세에는 신고된 경기의 두 플레이어 레이팅 전후 값(`match_players`)이 들어 있어 롤백 대상을 바로 확인할 수 있다.

```jsonc
// 상세 응답 200
{
  "data": {
    "reportId": 31,
    "status": "OPEN",
    "reason": "정답이 틀린 것 같아요",
    "createdAt": "2026-11-06T12:10:00+09:00",
    "question": { "questionId": 102, "content": "복합 인덱스 (a, b, c)가 있을 때...", "status": "ACTIVE" },
    "match": {
      "matchId": 5231,
      "rolledBack": false,
      "players": [
        { "userId": 1, "nickname": "코딩왕", "ratingBefore": 1000, "ratingAfter": 1016 },
        { "userId": 2, "nickname": "상대닉", "ratingBefore": 1040, "ratingAfter": 1024 }
      ]
    }
  }
}
```

`rolledBack`은 `rating_history`에 해당 경기의 `ROLLBACK` 이력이 있는지로 계산한다.

### 신고 처리 `PATCH /admin/reports/{reportId}/status`

```jsonc
// 요청
{ "status": "RESOLVED" }

// 응답 200
{ "data": { "reportId": 31, "status": "RESOLVED" } }
```

`RESOLVED`는 문제를 조치(숨김 등)하고 종료, `REJECTED`는 이상 없음으로 종료한다. 처리한 관리자와 시각(`resolved_by`, `resolved_at`)을 기록한다. 처리 메모를 저장할 컬럼은 ERD에 없다 **[미정]**.

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 409 | `ALREADY_PROCESSED` | 이미 처리가 끝난 신고 |
### 경기 레이팅 롤백 `POST /admin/matches/{matchId}/rating-rollback`

잘못된 문제로 진행된 경기 하나의 레이팅 변동분만 반대로 적용한다. 그 뒤에 치른 경기가 있어도 이후 경기의 결과는 건드리지 않아서, 롤백 후 값은 경기 전 값과 다를 수 있다.

```jsonc
// 요청
{ "reportId": 31 }

// 응답 200 (이후 다른 경기를 치른 경우의 예)
{
  "data": {
    "matchId": 5231,
    "players": [
      { "userId": 1, "reversedDelta": -16, "ratingAfter": 1004 },
      { "userId": 2, "reversedDelta": 16, "ratingAfter": 1056 }
    ]
  }
}
```

| HTTP | 에러 코드 | 상황 |
| --- | --- | --- |
| 404 | `MATCH_NOT_FOUND` | 없는 경기 |
| 409 | `ALREADY_ROLLED_BACK` | 이미 롤백한 경기 |
| 409 | `NOT_RATED_MATCH` | 레이팅이 반영되지 않은 경기 (비레이팅, 무효) |

롤백 규칙: `rating_history`의 해당 경기 `MATCH` 이력을 반대로 적용하고, 새 `ROLLBACK` 이력(`reversed_history_id`가 원본을 가리킴)을 남긴다. 원본 이력과 `match_players`의 `rating_before`, `rating_after`는 경기 당시 기록이라 바꾸지 않는다. 경기마다 한 번만 롤백할 수 있다.


### 개념 등록과 문제 생성 실행

`concepts` 테이블과 `questions.concept_id`는 ERD에 존재한다. 개념 등록 및 LLM 생성 실행의 별도 REST API는 현재 명세에 정의되어 있지 않다. LLM 호출은 게임 중이 아닌 배치에서만 수행한다(`docs/game-rules.md`).
