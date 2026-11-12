-- 기존 스키마에 한 번 적용. 새 버전 서버를 실행하기 전에 적용한다.
-- 새 DB는 schema.sql을 사용하며 이 파일을 추가 실행하지 않는다.
-- MySQL DDL은 자동 커밋되므로 적용 전 백업하고 서버를 중지한다.
ALTER TABLE practice_sessions
  DROP COLUMN time_limit_sec,
  ADD COLUMN learning_cycle int NOT NULL DEFAULT 0,
  ADD COLUMN status varchar(20) NOT NULL DEFAULT 'ABANDONED';

-- 이전 구현의 세부 풀이 기록은 복원할 수 없으므로 과거 요약은 회차 0에 보존한다.
UPDATE practice_sessions SET status = 'COMPLETED' WHERE ended_at IS NOT NULL;
UPDATE practice_sessions SET ended_at = CURRENT_TIMESTAMP WHERE ended_at IS NULL;
ALTER TABLE practice_sessions
  MODIFY learning_cycle int NOT NULL DEFAULT 1,
  MODIFY status varchar(20) NOT NULL DEFAULT 'IN_PROGRESS',
  MODIFY question_count int NOT NULL COMMENT '최대 10, 숨긴 미제출 문제는 제외';

CREATE TABLE practice_progress (
  user_id bigint PRIMARY KEY NOT NULL,
  learning_cycle int NOT NULL DEFAULT 1,
  FOREIGN KEY (user_id) REFERENCES users (user_id)
);

CREATE TABLE practice_answers (
  answer_id bigint PRIMARY KEY NOT NULL AUTO_INCREMENT,
  session_id bigint NOT NULL,
  question_id bigint NOT NULL,
  question_index int NOT NULL,
  category_id int NOT NULL,
  difficulty varchar(10) NOT NULL,
  content text NOT NULL,
  explanation text,
  selected_choice_id bigint,
  correct boolean,
  skipped boolean NOT NULL DEFAULT false,
  submitted_at datetime,
  CONSTRAINT uk_practice_question UNIQUE (session_id, question_id),
  CONSTRAINT uk_practice_position UNIQUE (session_id, question_index),
  FOREIGN KEY (session_id) REFERENCES practice_sessions (session_id),
  FOREIGN KEY (question_id) REFERENCES questions (question_id)
);

CREATE TABLE practice_choices (
  answer_id bigint NOT NULL,
  choice_id bigint NOT NULL,
  choice_no int NOT NULL,
  content varchar(500) NOT NULL,
  is_correct boolean NOT NULL,
  CONSTRAINT uk_practice_choice UNIQUE (answer_id, choice_no),
  FOREIGN KEY (answer_id) REFERENCES practice_answers (answer_id)
);

CREATE INDEX ix_practice_user_status ON practice_sessions (user_id, status);
CREATE INDEX ix_practice_user_cycle ON practice_sessions (user_id, learning_cycle);
ALTER TABLE saved_questions
  ADD COLUMN practice_session_id bigint,
  ADD FOREIGN KEY (practice_session_id) REFERENCES practice_sessions (session_id);
