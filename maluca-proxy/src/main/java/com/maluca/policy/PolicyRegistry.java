package com.maluca.policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.maluca.config.MalucaProperties;
import com.maluca.model.RateLimitConfig;
import com.maluca.policy.PolicyDefinition.FailMode;
import com.maluca.policy.PolicyDefinition.Mode;
import com.maluca.util.CidrSet;

import jakarta.annotation.PreDestroy;

/**
 * Loads policies from YAML and hot-reloads on file change.
 *
 * <p>Reload correctness: the new policy list is fully parsed and compiled
 * off to the side, then swapped in with one {@link AtomicReference#set} —
 * in-flight requests keep the list they already read, new requests get the
 * new one, and nobody ever observes a half-swapped state. A file that fails
 * to parse is logged and ignored; the last good config stays active.
 *
 * <p>Resolution is most-specific-wins via
 * {@link PathPattern#SPECIFICITY_COMPARATOR}: {@code /api/payments/refund}
 * beats {@code /api/payments/*} beats {@code /api/**}.
 */
@Component
public class PolicyRegistry {

    private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);
    private static final int MAX_POLICY_NAME_LENGTH = 128;
    private static final int MAX_POLICY_ROUTE_LENGTH = 512;

    private final YAMLMapper yaml = (YAMLMapper) new YAMLMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    private final PathPatternParser parser = new PathPatternParser();
    private final AtomicReference<List<CompiledPolicy>> active = new AtomicReference<>(List.of());
    private final MalucaProperties properties;
    private final Path policyFile;
    private volatile Thread watcherThread;
    private volatile WatchService watchService;

    public PolicyRegistry(MalucaProperties properties) {
        this.properties = properties;
        String configured = properties.policyFile();
        this.policyFile = configured == null || configured.isBlank() ? null : Path.of(configured);
        load();
        if (policyFile != null && Files.exists(policyFile)) {
            startWatcher();
        }
    }

    // ── Resolution (hot path) ─────────────────────────────────────────────────

    public CompiledPolicy resolve(String path, String tier) {
        PathContainer container = PathContainer.parsePath(path);
        List<CompiledPolicy> policies = active.get();
        CompiledPolicy best = null;
        for (CompiledPolicy policy : policies) {
            if (policy.matches(container, tier)
                    && (best == null
                        || PathPattern.SPECIFICITY_COMPARATOR.compare(policy.pattern(), best.pattern()) < 0)) {
                best = policy;
            }
        }
        return best; // may be null -> caller falls back to global defaults
    }

    public List<CompiledPolicy> snapshot() {
        return active.get();
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    public synchronized boolean load() {
        try {
            PolicyDefinition.PolicyFile file;
            if (policyFile != null && Files.exists(policyFile)) {
                file = yaml.readValue(Files.readAllBytes(policyFile), PolicyDefinition.PolicyFile.class);
                log.info("policies_loaded source={} count={}", policyFile,
                        file.policies() == null ? 0 : file.policies().size());
            } else {
                try (InputStream in = new ClassPathResource("policies.yml").getInputStream()) {
                    file = yaml.readValue(in, PolicyDefinition.PolicyFile.class);
                }
                log.info("policies_loaded source=classpath:policies.yml count={}",
                        file.policies() == null ? 0 : file.policies().size());
            }
            active.set(compile(file));
            return true;
        } catch (Exception e) {
            log.error("policies_reload_failed keeping_previous error={}", e.toString());
            return false;
        }
    }

    private List<CompiledPolicy> compile(PolicyDefinition.PolicyFile file) {
        List<CompiledPolicy> compiled = new ArrayList<>();
        if (file.policies() == null) {
            return compiled;
        }
        Set<String> names = new HashSet<>();
        for (PolicyDefinition def : file.policies()) {
            if (def == null) {
                throw new IllegalArgumentException("Policy definition cannot be null");
            }
            validateIdentity(def.name(), "name", MAX_POLICY_NAME_LENGTH);
            validateIdentity(def.route(), "route", MAX_POLICY_ROUTE_LENGTH);
            if (!names.add(def.name())) {
                throw new IllegalArgumentException("Duplicate policy name: " + def.name());
            }
            compiled.add(new CompiledPolicy(
                    def.name(),
                    parser.parse(def.route()),
                    def.tiers() == null ? Set.of() : Set.copyOf(def.tiers()),
                    def.mode() == null ? Mode.ENFORCE : def.mode(),
                    def.keying(),
                    toRateLimitConfig(def.rateLimit()),
                    toBands(def.bands()),
                    CidrSet.of(def.allowlist()),
                    CidrSet.of(def.denylist()),
                    def.failMode() == null ? FailMode.FAIL_OPEN : def.failMode()));
        }
        return List.copyOf(compiled);
    }

    private static void validateIdentity(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Policy " + field + " is required");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    "Policy " + field + " cannot exceed " + maximumLength + " characters");
        }
    }

    private RateLimitConfig toRateLimitConfig(PolicyDefinition.RateLimitSpec spec) {
        if (spec == null) {
            return null;
        }
        if (spec.algorithm() == null) {
            throw new IllegalArgumentException("policy rate-limit algorithm is required");
        }
        switch (spec.algorithm()) {
            case FIXED_WINDOW, SLIDING_WINDOW_COUNTER, SLIDING_WINDOW_LOG -> {
                if (spec.limit() <= 0 || spec.windowSeconds() <= 0) {
                    throw new IllegalArgumentException(
                            "window rate-limit requires positive limit and window-seconds");
                }
            }
            case TOKEN_BUCKET, LEAKY_BUCKET -> {
                if (!Double.isFinite(spec.ratePerSecond())
                        || spec.ratePerSecond() <= 0 || spec.burst() <= 0) {
                    throw new IllegalArgumentException(
                            "bucket rate-limit requires finite positive rate-per-second and burst");
                }
            }
        }
        return new RateLimitConfig(spec.algorithm(), spec.limit(), spec.windowSeconds(),
                spec.ratePerSecond(), spec.burst());
    }

    private MalucaProperties.Bands toBands(PolicyDefinition.BandsSpec spec) {
        if (spec == null) {
            return null;
        }
        MalucaProperties.Bands defaults = properties.bands();
        MalucaProperties.Bands resolved = new MalucaProperties.Bands(
                spec.observeMin() != null ? spec.observeMin() : defaults.observeMin(),
                spec.softLimitMin() != null ? spec.softLimitMin() : defaults.softLimitMin(),
                spec.hardLimitMin() != null ? spec.hardLimitMin() : defaults.hardLimitMin(),
                spec.challengeMin() != null ? spec.challengeMin() : defaults.challengeMin(),
                spec.blockMin() != null ? spec.blockMin() : defaults.blockMin());
        validateBands(resolved);
        return resolved;
    }

    private static void validateBands(MalucaProperties.Bands bands) {
        if (bands.observeMin() < 0 || bands.blockMin() > 100
                || !(bands.observeMin() < bands.softLimitMin()
                && bands.softLimitMin() < bands.hardLimitMin()
                && bands.hardLimitMin() < bands.challengeMin()
                && bands.challengeMin() < bands.blockMin())) {
            throw new IllegalArgumentException(
                    "resolved policy bands must be strictly increasing between 0 and 100");
        }
    }

    // ── Hot reload ────────────────────────────────────────────────────────────

    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path dir = policyFile.toAbsolutePath().getParent();
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            watcherThread = new Thread(this::watchLoop, "maluca-policy-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            log.info("policy_watcher_started file={}", policyFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("policy_watcher_failed error={}", e.toString());
        }
    }

    private void watchLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                boolean relevant = key.pollEvents().stream()
                        .anyMatch(e -> policyFile.getFileName().equals(e.context()));
                key.reset();
                if (relevant) {
                    Thread.sleep(100); // editors often write in multiple events
                    log.info("policy_file_changed reloading");
                    load();
                }
            }
        } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() throws IOException {
        if (watchService != null) {
            watchService.close();
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }
}
