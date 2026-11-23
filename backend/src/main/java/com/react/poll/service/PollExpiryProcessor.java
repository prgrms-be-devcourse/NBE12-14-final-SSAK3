package com.react.poll.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 제한 시간이 지난 투표를 마감한다. 투표마다 따로 트랜잭션을 열어 한 건이 실패해도 나머지가 마감되게 하며,
 * 마감 이벤트는 PollService 가 커밋 뒤에 방송한다. 스케줄러는 이 클래스를 주기적으로 부르기만 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PollExpiryProcessor {
    private final PollService polls;

    /** @return 이번에 마감한 투표 수 */
    public int closeExpiredPolls() {
        int closed = 0;
        for (long[] expired : polls.findExpiredPolls()) {
            try {
                if (polls.closeIfExpired(expired[0], expired[1])) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("만료 투표 마감 실패: roomId={}, pollId={}", expired[0], expired[1], e);
            }
        }
        return closed;
    }
}
