package com.react.poll.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1초마다 제한 시간이 지난 투표를 마감해 청중에게 마감을 알린다. 통합 테스트(test 프로파일)에서는 돌리지 않는다. */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PollExpiryScheduler {
    private final PollExpiryProcessor processor;

    @Scheduled(fixedDelayString = "${app.poll.expiry-fixed-delay:1000}", scheduler = "pollExpiryTaskScheduler")
    public void closeExpiredPolls() {
        try {
            processor.closeExpiredPolls();
        } catch (RuntimeException e) {
            log.warn("만료 투표 마감 스케줄러 실행 실패", e);
        }
    }
}
