ALTER TABLE polls ADD COLUMN closed_total_participants BIGINT NULL;

-- 기존 마감 투표는 초 단위 입장·마감 기록으로 복원한다. 응답을 남긴 참여자는 항상 포함한다.
UPDATE polls p
SET closed_total_participants = (
    SELECT COUNT(*) FROM lecture_participants lp
    WHERE lp.lecture_room_id = p.lecture_room_id
      AND (lp.joined_at < COALESCE(p.closed_at, p.closes_at)
           OR (COALESCE(p.close_reason, 'HOST') <> 'TIMEOUT'
               AND lp.joined_at = COALESCE(p.closed_at, p.closes_at))
           OR EXISTS (SELECT 1 FROM poll_responses r WHERE r.poll_id = p.id AND r.participant_id = lp.id))
)
WHERE p.status = 'CLOSED';
