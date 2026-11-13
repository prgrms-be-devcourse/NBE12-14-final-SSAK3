-- demo 프로파일의 별도 DB 전용. 정식 db/migration에는 포함하지 않는다.
-- 반복 적용 시에도 동일 예시를 중복 삽입하지 않는다.
INSERT INTO questions (category_id, difficulty, content, explanation, source, status)
SELECT 1, 'EASY', 'TCP의 특징으로 알맞은 것은?',
       'TCP는 연결 지향 프로토콜로, 순서 제어와 재전송을 통해 신뢰성 있는 전송을 제공합니다.', 'MANUAL', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM questions WHERE content = 'TCP의 특징으로 알맞은 것은?');

INSERT INTO question_choices (question_id, choice_no, content, is_correct)
SELECT q.question_id, c.choice_no, c.content, c.is_correct
FROM questions q
CROSS JOIN (
    SELECT 1 AS choice_no, '비연결형 통신' AS content, false AS is_correct
    UNION ALL SELECT 2, '전송 순서를 보장', true
    UNION ALL SELECT 3, '항상 UDP보다 빠름', false
    UNION ALL SELECT 4, '재전송을 지원하지 않음', false
) c
WHERE q.content = 'TCP의 특징으로 알맞은 것은?'
  AND NOT EXISTS (SELECT 1 FROM question_choices existing
                  WHERE existing.question_id = q.question_id AND existing.choice_no = c.choice_no);
