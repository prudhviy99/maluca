package com.maluca;

import java.util.List;

import com.maluca.config.MalucaProperties;

/** Canonical config fixture matching the application.yml defaults. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static MalucaProperties defaultProperties() {
        return properties(false, List.of());
    }

    public static MalucaProperties properties(boolean trustXff, List<String> trustedProxies) {
        return new MalucaProperties(
                new MalucaProperties.Upstream("http://localhost:8081", 5000, 30000, 100),
                new MalucaProperties.Identity(trustXff, trustedProxies),
                new MalucaProperties.Limits(true, 30, 10, 300, 5),
                new MalucaProperties.Scoring(
                        new MalucaProperties.Scoring.Weights(40, 25, 20, 25, 15, 15, 60, 15, 8, 30, 20),
                        new MalucaProperties.Scoring.Thresholds(30, 120, 15, 20, 10)),
                new MalucaProperties.Bands(30, 50, 65, 75, 90),
                new MalucaProperties.Hysteresis(30, 120, 300),
                new MalucaProperties.Mitigation(500),
                List.of("/login", "/admin", "/api/auth"));
    }
}
