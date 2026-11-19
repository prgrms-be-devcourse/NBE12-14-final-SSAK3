package com.react.ai.config;

import com.react.ai.service.AiAnalysisFinalizationScheduler;
import com.react.ai.service.AiAnalysisStaleRecoveryScheduler;
import com.react.ai.service.AudienceAnalysisScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisSchedulingConfigurationTest {
    @Test
    void live_finalization_staleRecovery는_서로_다른_전용_scheduler를_사용한다() throws NoSuchMethodException {
        Scheduled live = AudienceAnalysisScheduler.class
                .getMethod("processCompletedRegularWindows")
                .getAnnotation(Scheduled.class);
        Scheduled finalization = AiAnalysisFinalizationScheduler.class
                .getMethod("processPendingFinalizations")
                .getAnnotation(Scheduled.class);
        Scheduled staleRecovery = AiAnalysisStaleRecoveryScheduler.class
                .getMethod("recoverStaleAnalyses")
                .getAnnotation(Scheduled.class);

        assertThat(live.scheduler()).isEqualTo("aiLiveAnalysisTaskScheduler");
        assertThat(finalization.scheduler()).isEqualTo("aiFinalizationTaskScheduler");
        assertThat(staleRecovery.scheduler()).isEqualTo("aiStaleRecoveryTaskScheduler");
    }

    @Test
    void finalization_작업이_대기해도_live_작업은_별도_스레드에서_실행된다() throws InterruptedException {
        AiAnalysisSchedulingConfiguration configuration = new AiAnalysisSchedulingConfiguration();
        ThreadPoolTaskScheduler liveScheduler = configuration.aiLiveAnalysisTaskScheduler();
        ThreadPoolTaskScheduler finalizationScheduler = configuration.aiFinalizationTaskScheduler();
        ThreadPoolTaskScheduler staleRecoveryScheduler = configuration.aiStaleRecoveryTaskScheduler();
        liveScheduler.initialize();
        finalizationScheduler.initialize();
        staleRecoveryScheduler.initialize();

        CountDownLatch staleRecoveryStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleRecovery = new CountDownLatch(1);
        CountDownLatch liveExecuted = new CountDownLatch(1);
        CountDownLatch finalizationExecuted = new CountDownLatch(1);
        try {
            staleRecoveryScheduler.execute(() -> {
                staleRecoveryStarted.countDown();
                try {
                    releaseStaleRecovery.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(staleRecoveryStarted.await(1, TimeUnit.SECONDS)).isTrue();
            liveScheduler.execute(liveExecuted::countDown);
            finalizationScheduler.execute(finalizationExecuted::countDown);

            assertThat(liveExecuted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(finalizationExecuted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseStaleRecovery.countDown();
            liveScheduler.shutdown();
            finalizationScheduler.shutdown();
            staleRecoveryScheduler.shutdown();
        }
    }
}
