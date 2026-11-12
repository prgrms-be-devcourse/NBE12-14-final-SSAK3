CREATE TABLE `users` (
  `user_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '회원 ID',
  `email` varchar(255) UNIQUE NOT NULL COMMENT '로그인 이메일',
  `password_hash` varchar(255) COMMENT '비밀번호 해시 (소셜 로그인 시 NULL) 소셜 로그인 미정',
  `nickname` varchar(30) UNIQUE NOT NULL COMMENT '닉네임',
  `profile_image_url` varchar(500) COMMENT '프로필 이미지 URL (Cloudinary), NULL이면 기본 이미지',
  `profile_image_public_id` varchar(255) COMMENT 'Cloudinary public_id (이미지 교체·삭제용)',
  `rating` int NOT NULL DEFAULT 1000 COMMENT '초기 레이팅 1000으로 확정',
  `role` varchar(10) NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
  `status` varchar(10) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / WITHDRAWN',
  `created_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP) COMMENT '가입일',
  `withdrawn_at` datetime COMMENT '탈퇴일'
);

CREATE TABLE `saved_questions` (
  `user_id` bigint NOT NULL COMMENT '회원',
  `question_id` bigint NOT NULL COMMENT '저장한 문제',
  `match_id` bigint COMMENT '저장한 경기',
  `saved_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP),
  PRIMARY KEY (`user_id`, `question_id`)
);

CREATE TABLE `match_players` (
  `match_id` bigint NOT NULL COMMENT '경기',
  `user_id` bigint NOT NULL COMMENT '참가자',
  `ready_at` datetime(3) COMMENT '준비 완료 시각 (둘 다 채워지면 경기 시작)',
  `score` int NOT NULL DEFAULT 0 COMMENT '현재 누적 점수, 종료 후 최종 점수 (음수 허용)',
  `result` varchar(10) COMMENT 'WIN/LOSE/DRAW/FORFEIT',
  `rating_before` int COMMENT '경기 전 레이팅',
  `rating_after` int COMMENT '경기 후 레이팅',
  PRIMARY KEY (`match_id`, `user_id`)
);

CREATE TABLE `rating_tiers` (
  `tier_id` int PRIMARY KEY NOT NULL COMMENT '티어 ID',
  `tier_name` varchar(20) NOT NULL COMMENT '브론즈/실버/골드/플래티넘/다이아/마스터',
  `min_rating` int NOT NULL COMMENT '구간 최소',
  `max_rating` int COMMENT '구간 최대 (마스터는 NULL)'
);

CREATE TABLE `questions` (
  `question_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '문제 ID',
  `category_id` int NOT NULL COMMENT '분야',
  `difficulty` varchar(10) NOT NULL COMMENT '쉬움/보통/어려움',
  `content` text NOT NULL COMMENT '문제 본문',
  `explanation` text COMMENT '해설',
  `source` varchar(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI',
  `status` varchar(10) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT(검수대기) / ACTIVE / HIDDEN',
  `created_by` bigint COMMENT '등록 관리자',
  `created_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` datetime,
  `concept_id` int COMMENT '출제 근거 개념 (AI 생성 문제, MANUAL 은 NULL 가능)'
);

CREATE TABLE `categories` (
  `category_id` int PRIMARY KEY NOT NULL COMMENT '분야 ID',
  `name` varchar(30) NOT NULL COMMENT '데이터베이스/네트워크/운영체제 등'
);

CREATE TABLE `match_rounds` (
  `round_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '라운드 ID',
  `match_id` bigint NOT NULL COMMENT '경기',
  `round_no` int NOT NULL COMMENT '라운드 번호',
  `question_id` bigint NOT NULL COMMENT '출제 문제 (출제된 문제는 수정 금지)',
  `status` varchar(20) NOT NULL DEFAULT 'READY' COMMENT 'READY/OPEN/EXPLANATION/FINISHED',
  `correct_score` int NOT NULL COMMENT '출제 시점의 정답 점수: 쉬움 20 / 보통 30 / 어려움 40',
  `wrong_score_delta` int NOT NULL COMMENT '출제 시점의 오답 감점: 쉬움 -10 / 보통 -15 / 어려움 -20',
  `winner_id` bigint COMMENT '먼저 처리된 정답 요청의 제출자 (라운드당 1명, 없으면 NULL)',
  `started_at` datetime(3),
  `deadline_at` datetime(3) COMMENT '답안 접수 마감 예정 시각',
  `ended_at` datetime(3) COMMENT '실제 종료 시각 (정답이 빠르면 deadline_at보다 앞섬)'
);

CREATE TABLE `practice_sessions` (
  `session_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '연습 ID',
  `user_id` bigint NOT NULL COMMENT '회원',
  `category_id` int COMMENT '선택 분야',
  `question_count` int NOT NULL COMMENT '문제 수 미정',
  `time_limit_sec` int COMMENT '제한 시간 미정',
  `correct_count` int NOT NULL DEFAULT 0,
  `started_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP),
  `ended_at` datetime
);

CREATE TABLE `question_reports` (
  `report_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '신고 ID',
  `question_id` bigint NOT NULL COMMENT '신고 대상 문제',
  `reporter_id` bigint NOT NULL COMMENT '신고자',
  `match_id` bigint NOT NULL COMMENT '신고가 발생한 경기',
  `reason` varchar(500) NOT NULL COMMENT '오류 내용',
  `status` varchar(10) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED/REJECTED',
  `created_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP),
  `resolved_by` bigint COMMENT '처리 관리자',
  `resolved_at` datetime
);

CREATE TABLE `round_answers` (
  `answer_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '제출 ID',
  `round_id` bigint NOT NULL COMMENT '라운드',
  `user_id` bigint NOT NULL COMMENT '제출자',
  `choice_id` bigint NOT NULL COMMENT '선택한 보기',
  `request_id` varchar(36) NOT NULL COMMENT '라운드 내 재전송 식별용 요청 ID (멱등성)',
  `received_sequence` bigint NOT NULL COMMENT '라운드 내 서버가 부여한 처리 순번',
  `verdict` varchar(20) NOT NULL COMMENT 'CORRECT(먼저 처리된 정답, 득점 1건만) / WRONG(오답, 제출마다 감점) / LATE(정답이지만 이미 승자 있음·점수 없음) / REJECTED(무효 요청)',
  `score_delta` int NOT NULL DEFAULT 0 COMMENT '반영된 점수: CORRECT는 +correct_score, WRONG은 wrong_score_delta, 그 외 0',
  `submitted_at` datetime(3) NOT NULL COMMENT '서버 수신 시각 (클라이언트 시각 아님)'
);

CREATE TABLE `matches` (
  `match_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '경기 ID',
  `match_type` varchar(10) NOT NULL COMMENT 'RATED(레이팅) / UNRATED(비레이팅)',
  `status` varchar(20) NOT NULL DEFAULT 'PREPARING' COMMENT 'PREPARING(입장·준비 대기)/PLAYING/FINISHED/CANCELED. 매칭 대기열은 DB 밖에서 관리',
  `total_rounds` int NOT NULL DEFAULT 10 COMMENT '총 라운드 수',
  `round_time_limit_sec` int NOT NULL DEFAULT 20 COMMENT '라운드 제한 시간(초)',
  `winner_id` bigint COMMENT '승자 (무승부 NULL)',
  `started_at` datetime(3),
  `ended_at` datetime(3)
);

CREATE TABLE `rating_history` (
  `history_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '이력 ID',
  `user_id` bigint NOT NULL COMMENT '회원',
  `match_id` bigint NOT NULL COMMENT '관련 경기',
  `delta` int NOT NULL COMMENT '변동값',
  `rating_after` int NOT NULL COMMENT '변동 후 레이팅',
  `reason` varchar(20) NOT NULL COMMENT 'MATCH / ROLLBACK',
  `reversed_history_id` bigint COMMENT '롤백이 취소한 원본 이력',
  `created_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `question_choices` (
  `choice_id` bigint PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '보기 ID',
  `question_id` bigint NOT NULL COMMENT '문제',
  `choice_no` tinyint NOT NULL COMMENT '보기 번호 1~4',
  `content` varchar(500) NOT NULL COMMENT '보기 내용',
  `is_correct` tinyint(1) NOT NULL DEFAULT 0 COMMENT '정답 여부'
);

CREATE TABLE `concepts` (
  `concept_id` int PRIMARY KEY NOT NULL AUTO_INCREMENT COMMENT '개념 ID',
  `category_id` int NOT NULL COMMENT '분야',
  `name` varchar(100) UNIQUE NOT NULL COMMENT '개념명',
  `description` text NOT NULL COMMENT 'LLM 문제 생성의 근거가 되는 개념 설명',
  `created_at` datetime NOT NULL DEFAULT (CURRENT_TIMESTAMP)
);

CREATE UNIQUE INDEX `match_rounds_index_0` ON `match_rounds` (`match_id`, `round_no`);

CREATE UNIQUE INDEX `match_rounds_index_1` ON `match_rounds` (`match_id`, `question_id`);

CREATE UNIQUE INDEX `question_reports_index_2` ON `question_reports` (`question_id`, `reporter_id`, `match_id`);

CREATE UNIQUE INDEX `round_answers_index_3` ON `round_answers` (`round_id`, `request_id`);

CREATE UNIQUE INDEX `round_answers_index_4` ON `round_answers` (`round_id`, `received_sequence`);

CREATE UNIQUE INDEX `rating_history_index_5` ON `rating_history` (`match_id`, `user_id`, `reason`);

CREATE UNIQUE INDEX `question_choices_index_6` ON `question_choices` (`question_id`, `choice_no`);

ALTER TABLE `concepts` ADD FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`);

ALTER TABLE `questions` ADD FOREIGN KEY (`concept_id`) REFERENCES `concepts` (`concept_id`);

ALTER TABLE `saved_questions` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `saved_questions` ADD FOREIGN KEY (`question_id`) REFERENCES `questions` (`question_id`);

ALTER TABLE `saved_questions` ADD FOREIGN KEY (`match_id`) REFERENCES `matches` (`match_id`);

ALTER TABLE `match_players` ADD FOREIGN KEY (`match_id`) REFERENCES `matches` (`match_id`);

ALTER TABLE `match_players` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `questions` ADD FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`);

ALTER TABLE `questions` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`);

ALTER TABLE `match_rounds` ADD FOREIGN KEY (`match_id`) REFERENCES `matches` (`match_id`);

ALTER TABLE `match_rounds` ADD FOREIGN KEY (`question_id`) REFERENCES `questions` (`question_id`);

ALTER TABLE `match_rounds` ADD FOREIGN KEY (`winner_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `practice_sessions` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `practice_sessions` ADD FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`);

ALTER TABLE `question_reports` ADD FOREIGN KEY (`question_id`) REFERENCES `questions` (`question_id`);

ALTER TABLE `question_reports` ADD FOREIGN KEY (`reporter_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `question_reports` ADD FOREIGN KEY (`resolved_by`) REFERENCES `users` (`user_id`);

ALTER TABLE `question_reports` ADD FOREIGN KEY (`match_id`) REFERENCES `matches` (`match_id`);

ALTER TABLE `round_answers` ADD FOREIGN KEY (`round_id`) REFERENCES `match_rounds` (`round_id`);

ALTER TABLE `round_answers` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `round_answers` ADD FOREIGN KEY (`choice_id`) REFERENCES `question_choices` (`choice_id`);

ALTER TABLE `matches` ADD FOREIGN KEY (`winner_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `rating_history` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

ALTER TABLE `rating_history` ADD FOREIGN KEY (`match_id`) REFERENCES `matches` (`match_id`);

ALTER TABLE `rating_history` ADD FOREIGN KEY (`reversed_history_id`) REFERENCES `rating_history` (`history_id`);

ALTER TABLE `question_choices` ADD FOREIGN KEY (`question_id`) REFERENCES `questions` (`question_id`);
