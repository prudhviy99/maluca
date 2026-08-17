package com.maluca.mcp.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.maluca.mcp.TestProperties;

class MalucaMcpPropertiesTest {

    @Test
    void queryTimeoutMustHaveAtLeastMillisecondPrecision() {
        assertThatThrownBy(() -> TestProperties.withPromqlTimeout(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1ms and 30s");
        assertThatThrownBy(() -> TestProperties.withPromqlTimeout(Duration.ofNanos(1_500_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1ms and 30s");
    }
}
