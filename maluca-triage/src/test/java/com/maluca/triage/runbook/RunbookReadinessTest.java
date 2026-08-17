package com.maluca.triage.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class RunbookReadinessTest {

    @Test
    void readinessIsFailClosedUntilTrustedCorpusIsAvailable() {
        RunbookReadiness readiness = new RunbookReadiness();

        assertThat(readiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");

        readiness.ready("trusted corpus available; chunks=35");

        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
        readiness.requireReady();
    }

    @Test
    void laterFailedRefreshMakesRetrievalUnavailableUntilLastGoodStateIsReconfirmed() {
        RunbookReadiness readiness = new RunbookReadiness();
        readiness.ready("trusted corpus available");

        readiness.unavailable("ingestion attempt failed");

        assertThat(readiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThatThrownBy(readiness::requireReady).isInstanceOf(IllegalStateException.class);
    }
}
