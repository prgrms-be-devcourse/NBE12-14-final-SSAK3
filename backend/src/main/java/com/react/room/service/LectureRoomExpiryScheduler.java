package com.react.room.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class LectureRoomExpiryScheduler {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final LectureRoomService lectureRoomService;
    private final Duration maxLectureDuration;

    public LectureRoomExpiryScheduler(
            LectureRoomService lectureRoomService,
            @Value("${app.limits.max-lecture-duration:180m}")
            Duration maxLectureDuration
    ) {
        if (maxLectureDuration == null
                || maxLectureDuration.isZero()
                || maxLectureDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "최대 강연 시간은 0보다 커야 합니다."
            );
        }

        this.lectureRoomService = lectureRoomService;
        this.maxLectureDuration = maxLectureDuration;
    }

    // 기존 AI 및 투표 스케줄러와 실행 스레드를 분리한다.
    @Bean(name = "lectureRoomExpiryTaskScheduler")
    public ThreadPoolTaskScheduler lectureRoomExpiryTaskScheduler() {

        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("lecture-room-expiry-");

        return scheduler;
    }

    // 최대 강연 시간을 초과한 LIVE 강연방을 자동 종료한다.
    @Scheduled(
            fixedDelayString = "${app.limits.expiry-check-interval-ms:1000}",
            scheduler = "lectureRoomExpiryTaskScheduler"
    )
    public void expireRooms() {

        LocalDateTime cutoff = LocalDateTime.now(SEOUL)
                .minus(maxLectureDuration);

        List<Long> expiredRoomIds =
                lectureRoomService.findExpiredRoomIds(cutoff);

        for (Long roomId : expiredRoomIds) {

            try {
                boolean ended = lectureRoomService.endExpiredRoom(
                        roomId,
                        cutoff
                );

                if (ended) {
                    log.info(
                            "최대 강연 시간 초과로 자동 종료: roomId={}",
                            roomId
                    );
                }

            } catch (Exception e) {
                // 특정 강연방의 종료 실패가 다른 강연방에 영향을 주지 않도록 한다.
                log.error(
                        "강연방 자동 종료 실패: roomId={}",
                        roomId,
                        e
                );
            }
        }
    }
}