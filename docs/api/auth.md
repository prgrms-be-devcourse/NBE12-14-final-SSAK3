# AUTH — 강연자 인증


### [AUTH-01] 강연자 회원가입

**POST** `/api/v1/auth/signup` — 인증 불필요

Request

```json
{ "email": "host@example.com", "password": "ExamplePassword123!", "name": "김강연" }
```

Response — 201 Created

```json
{ "userId": 1, "email": "host@example.com", "name": "김강연", "createdAt": "2026-11-17T00:00:00Z" }
```

규칙: 이메일 형식 검증, 중복 가입 차단, 비밀번호 정책 검증, 비밀번호 해시 저장, 비밀번호·해시는 응답에 포함하지 않음, 가입 후 자동 로그인은 하지 않는 방식을 제안한다.
오류: `400 INVALID_INPUT`, `409 EMAIL_ALREADY_EXISTS` · 엔터티: `users`

### [AUTH-02] 강연자 로그인

**POST** `/api/v1/auth/login` — 인증 불필요

Request

```json
{ "email": "host@example.com", "password": "ExamplePassword123!" }
```

Response — 200 OK

```json
{ "userId": 1, "email": "host@example.com", "name": "김강연", "authenticated": true }
```

응답 헤더 예시: `Set-Cookie: HOST_SESSION=...; HttpOnly; Secure; SameSite=Lax`
규칙: 이메일로 사용자 조회, 암호화된 비밀번호 검증, 성공 시 세션 생성, 실패 시 인증 정보 미발급.
오류: `401 INVALID_CREDENTIALS` · 엔터티: `users`

### [AUTH-03] 현재 로그인 사용자 조회

**GET** `/api/v1/auth/me` — HOST

Response — 200 OK

```json
{ "userId": 1, "email": "host@example.com", "name": "김강연" }
```

규칙: 서버 인증 정보로 현재 사용자 식별(클라이언트가 보낸 userId 를 신뢰하지 않음), 비밀번호 정보 노출 금지.
오류: `401 UNAUTHENTICATED`

### [AUTH-04] 강연자 로그아웃

**POST** `/api/v1/auth/logout` — HOST — Response 204 No Content

규칙: 서버 세션 무효화, 인증 쿠키 만료, 로그아웃과 강연 종료는 별개이며 기존 강연 기록은 삭제하지 않음.

