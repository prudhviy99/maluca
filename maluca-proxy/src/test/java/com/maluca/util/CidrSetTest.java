package com.maluca.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CidrSetTest {

    @Test
    void matchesLiteralIpv4AndIpv6Networks() {
        CidrSet set = CidrSet.of(List.of("192.0.2.0/24", "2001:db8::/32"));

        assertThat(set.contains("192.0.2.25")).isTrue();
        assertThat(set.contains("198.51.100.1")).isFalse();
        assertThat(set.contains("2001:db8::1")).isTrue();
    }

    @Test
    void rejectsHostnamesAndOutOfRangePrefixes() {
        assertThatThrownBy(() -> CidrSet.of(List.of("example.com/32")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrSet.of(List.of("192.0.2.0/33")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrSet.of(List.of("2001:db8::/129")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CidrSet.of(List.of("192.0.2.0/24")).contains("localhost")).isFalse();
    }
}
