package com.maluca.triage.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigurationTest {

    @Test
    void scheduledControlPlaneWorkCanRunConcurrently() throws Exception {
        var scheduler = (ThreadPoolTaskScheduler) new SchedulingConfiguration().taskScheduler();
        scheduler.initialize();
        try {
            CountDownLatch blockersStarted = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch independentRan = new CountDownLatch(1);
            for (int index = 0; index < 3; index++) {
                scheduler.schedule(() -> {
                    blockersStarted.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, java.time.Instant.now());
            }
            assertThat(blockersStarted.await(1, TimeUnit.SECONDS)).isTrue();

            scheduler.schedule(independentRan::countDown, java.time.Instant.now());

            assertThat(independentRan.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.getPoolSize()).isGreaterThanOrEqualTo(4);
            release.countDown();
        } finally {
            scheduler.destroy();
        }
    }
}
