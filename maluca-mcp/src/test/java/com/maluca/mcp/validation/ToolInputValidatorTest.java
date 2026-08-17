package com.maluca.mcp.validation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.maluca.mcp.TestProperties;

class ToolInputValidatorTest {

    private final ToolInputValidator validator = new ToolInputValidator(TestProperties.defaults());

    @Test
    void evidenceWindowsMustBeCompletePositiveAndBounded() {
        Instant start = Instant.parse("2026-08-12T10:00:00Z");

        assertThatThrownBy(() -> validator.validateWindow(start, null))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("provided together");
        assertThatThrownBy(() -> validator.validateWindow(start, start))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("after from");
        assertThatThrownBy(() -> validator.validateWindow(start, start.plusSeconds(86_401)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("window exceeds");
    }
}
