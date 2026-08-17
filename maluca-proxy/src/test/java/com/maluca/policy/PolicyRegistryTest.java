package com.maluca.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.maluca.TestFixtures;
import com.maluca.config.MalucaProperties;
import com.maluca.model.RateLimitAlgorithm;
import com.maluca.policy.PolicyDefinition.FailMode;
import com.maluca.policy.PolicyDefinition.Mode;

class PolicyRegistryTest {

    private static MalucaProperties withPolicyFile(String path) {
        MalucaProperties base = TestFixtures.defaultProperties();
        return new MalucaProperties(base.upstream(), base.identity(), base.limits(),
                base.scoring(), base.bands(), base.hysteresis(), base.mitigation(),
                base.resilience(), base.challenge(), base.sensitivePaths(), path,
                base.adminToken(), base.tierKeys());
    }

    // ── Classpath fallback + resolution ──────────────────────────────────────

    @Test
    void loadsClasspathDefaultsWhenNoFileConfigured() {
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(""));

        assertThat(registry.snapshot()).isNotEmpty();
        assertThat(registry.resolve("/login", "anonymous").name()).isEqualTo("login");
    }

    @Test
    void mostSpecificRouteWins() {
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(""));

        assertThat(registry.resolve("/login", "anonymous").name()).isEqualTo("login");
        assertThat(registry.resolve("/api/checkout", "anonymous").name()).isEqualTo("checkout");
        assertThat(registry.resolve("/api/products", "anonymous").name()).isEqualTo("api");
        assertThat(registry.resolve("/some/random/page", "anonymous").name()).isEqualTo("default");
    }

    @Test
    void policyCarriesAlgorithmBandsAndFailMode() {
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(""));

        CompiledPolicy login = registry.resolve("/login", "anonymous");
        assertThat(login.rateLimit().algorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW_LOG);
        assertThat(login.rateLimit().limit()).isEqualTo(5);
        assertThat(login.bands().blockMin()).isEqualTo(80);
        assertThat(login.failMode()).isEqualTo(FailMode.FAIL_CLOSED);

        CompiledPolicy fallback = registry.resolve("/anything", "anonymous");
        assertThat(fallback.rateLimit()).as("default policy uses global limiter").isNull();
        assertThat(fallback.failMode()).isEqualTo(FailMode.FAIL_OPEN);
    }

    // ── File loading, tiers, modes, lists ─────────────────────────────────────

    @Test
    void loadsFromFileWithTiersModesAndLists(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: enterprise-api
                    route: /api/**
                    tiers: [enterprise]
                    mode: DRY_RUN
                    allowlist: [10.0.0.0/8]
                    denylist: [6.6.6.6]
                  - name: default
                    route: /**
                """);
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));

        CompiledPolicy enterprise = registry.resolve("/api/x", "enterprise");
        assertThat(enterprise.name()).isEqualTo("enterprise-api");
        assertThat(enterprise.mode()).isEqualTo(Mode.DRY_RUN);
        assertThat(enterprise.isDryRun()).isTrue();
        assertThat(enterprise.allowlist().contains("10.1.2.3")).isTrue();
        assertThat(enterprise.denylist().contains("6.6.6.6")).isTrue();

        // tier mismatch falls through to the catch-all
        assertThat(registry.resolve("/api/x", "anonymous").name()).isEqualTo("default");
    }

    @Test
    void badFileKeepsPreviousPolicies(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: only
                    route: /**
                """);
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));
        assertThat(registry.snapshot()).hasSize(1);

        Files.writeString(file, "policies:\n  - route-without-name: zzz {{{{");
        boolean ok = registry.load();

        assertThat(ok).isFalse();
        assertThat(registry.snapshot()).as("last good config stays active").hasSize(1);
        assertThat(registry.resolve("/x", "anonymous").name()).isEqualTo("only");
    }

    @Test
    void invalidPolicyIdentitiesKeepPreviousPolicies(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: stable
                    route: /**
                """);
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));

        List<String> invalidFiles = List.of(
                """
                policies:
                  - name: ' '
                    route: /**
                """,
                """
                policies:
                  - name: %s
                    route: /**
                """.formatted("n".repeat(129)),
                """
                policies:
                  - name: blank-route
                    route: ' '
                """,
                """
                policies:
                  - name: long-route
                    route: %s
                """.formatted("/" + "r".repeat(512)),
                """
                policies:
                  - name: duplicate
                    route: /one
                  - name: duplicate
                    route: /two
                """);

        for (String invalidFile : invalidFiles) {
            Files.writeString(file, invalidFile);

            assertThat(registry.load()).as("invalid reload is rejected").isFalse();
            assertThat(registry.snapshot()).as("last good config stays active").hasSize(1);
            assertThat(registry.resolve("/x", "anonymous").name()).isEqualTo("stable");
        }
    }

    @Test
    void policyIdentityWireBoundsAreInclusive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        String name = "n".repeat(128);
        String route = "/" + "r".repeat(511);
        Files.writeString(file, """
                policies:
                  - name: %s
                    route: %s
                """.formatted(name, route));

        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));

        assertThat(registry.snapshot()).singleElement()
                .extracting(CompiledPolicy::name)
                .isEqualTo(name);
    }

    @Test
    void resolvedPartialBandsMustRemainStrictlyIncreasing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: only
                    route: /**
                    bands:
                      hard-limit-min: 20
                """);

        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));

        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.load()).isFalse();
    }

    @Test
    void invalidAlgorithmSpecificRateValuesRejectTheReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: only
                    route: /**
                    rate-limit:
                      algorithm: TOKEN_BUCKET
                      rate-per-second: 0
                      burst: 0
                """);

        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));

        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.load()).isFalse();
    }

    @Test
    void hotReloadPicksUpFileChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: v1
                    route: /**
                """);
        PolicyRegistry registry = new PolicyRegistry(withPolicyFile(file.toString()));
        assertThat(registry.resolve("/x", "anonymous").name()).isEqualTo("v1");

        Files.writeString(file, """
                policies:
                  - name: v2
                    route: /**
                """);

        // the watcher thread needs a beat; poll up to 5s
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if ("v2".equals(registry.resolve("/x", "anonymous").name())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(registry.resolve("/x", "anonymous").name()).isEqualTo("v2");
    }
}
