package com.react.ai.config;

import com.react.ai.service.AiAnalysisFinalizationScheduler;
import com.react.ai.service.AudienceAnalysisScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisSchedulingConfigurationTest {
    @Test
    void live와_finalization은_서로_다른_전용_scheduler를_사용한다() throws NoSuchMethodException {
        Scheduled live = AudienceAnalysisScheduler.class
                .getMethod("processCompletedRegularWindows")
                .getAnnotation(Scheduled.class);
        Scheduled finalization = AiAnalysisFinalizationScheduler.class
                .getMethod("processPendingFinalizations")
                .getAnnotation(Scheduled.class);

        assertThat(live.scheduler()).isEqualTo("aiLiveAnalysisTaskScheduler");
        assertThat(finalization.scheduler()).isEqualTo("aiFinalizationTaskScheduler");
    }

    @Test
    void finalization_작업이_대기해도_live_작업은_별도_스레드에서_실행된다() throws InterruptedException {
        AiAnalysisSchedulingConfiguration configuration = new AiAnalysisSchedulingConfiguration();
        ThreadPoolTaskScheduler liveScheduler = configuration.aiLiveAnalysisTaskScheduler();
        ThreadPoolTaskScheduler finalizationScheduler = configuration.aiFinalizationTaskScheduler();
        liveScheduler.initialize();
        finalizationScheduler.initialize();

        CountDownLatch finalizationStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        CountDownLatch liveExecuted = new CountDownLatch(1);
        try {
            finalizationScheduler.execute(() -> {
                finalizationStarted.countDown();
                try {
                    releaseFinalization.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(finalizationStarted.await(1, TimeUnit.SECONDS)).isTrue();
            liveScheduler.execute(liveExecuted::countDown);

            assertThat(liveExecuted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFinalization.countDown();
            liveScheduler.shutdown();
            finalizationScheduler.shutdown();
        }
    }
}
