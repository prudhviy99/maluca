package com.maluca.triage.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiConfigurationTest {

    @Test
    void productionChatDisablesThinkingForStrictJsonParityWithEvaluation() {
        var options = AiConfiguration.productionChatOptions();

        assertThat(options.getThinkOption()).isNotNull();
        assertThat(options.getThinkOption().toJsonValue()).isEqualTo(false);
    }
}
