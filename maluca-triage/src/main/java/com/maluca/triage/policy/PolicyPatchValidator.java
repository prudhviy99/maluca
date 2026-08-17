package com.maluca.triage.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.maluca.contracts.incident.IncidentView;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.triage.config.TriageProperties;

/** Strict semantic gate for model-authored policy deltas. */
@Component
public class PolicyPatchValidator {

    private static final Set<String> MODES = Set.of("ENFORCE", "OBSERVE", "DRY_RUN");
    private static final Set<String> KEYING = Set.of("NETWORK", "COMPOSITE", "FINGERPRINT");
    private static final Set<String> FAIL_MODES = Set.of("FAIL_OPEN", "FAIL_CLOSED");
    private static final Set<String> ALGORITHMS = Set.of(
            "FIXED_WINDOW", "SLIDING_WINDOW_COUNTER", "SLIDING_WINDOW_LOG",
            "TOKEN_BUCKET", "LEAKY_BUCKET");
    private static final int MAX_NETWORK_ENTRIES = 100;

    private final TriageProperties.Policy defaults;

    public PolicyPatchValidator(TriageProperties properties) {
        this.defaults = properties.policy();
    }

    public List<String> validate(PolicyPatch patch, IncidentView incident) {
        if (patch == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        if (incident.policyName().startsWith("__maluca_")) {
            errors.add("system incidents cannot propose route policy mutations");
        }
        if (!incident.policyName().equals(patch.policyName())) {
            errors.add("patch policyName must equal the incident policy");
        }
        if (!incident.policyRoute().equals(patch.route())) {
            errors.add("patch route must equal the incident route");
        }
        allowed(errors, "mode", patch.mode(), MODES);
        allowed(errors, "keying", patch.keying(), KEYING);
        allowed(errors, "failMode", patch.failMode(), FAIL_MODES);
        validateRateLimit(patch.rateLimit(), errors);
        validateBands(patch.bands(), errors);
        validateCidrs("addAllowlist", patch.addAllowlist(), errors);
        validateCidrs("removeAllowlist", patch.removeAllowlist(), errors);
        validateCidrs("addDenylist", patch.addDenylist(), errors);
        validateCidrs("removeDenylist", patch.removeDenylist(), errors);
        int networkEntries = patch.addAllowlist().size() + patch.removeAllowlist().size()
                + patch.addDenylist().size() + patch.removeDenylist().size();
        if (networkEntries > MAX_NETWORK_ENTRIES) {
            errors.add("patch cannot contain more than " + MAX_NETWORK_ENTRIES + " network entries");
        }
        rejectOverlap(errors, "allowlist entry cannot be both added and removed",
                patch.addAllowlist(), patch.removeAllowlist());
        rejectOverlap(errors, "denylist entry cannot be both added and removed",
                patch.addDenylist(), patch.removeDenylist());
        rejectOverlap(errors, "entry cannot be added to both allowlist and denylist",
                patch.addAllowlist(), patch.addDenylist());
        if (patch.rationale() == null || patch.rationale().isBlank()
                || patch.rationale().length() > 2_000
                || patch.rationale().chars().anyMatch(Character::isISOControl)) {
            errors.add("patch rationale must contain 1 to 2000 characters");
        }
        boolean changesNothing = patch.mode() == null && patch.keying() == null
                && patch.rateLimit() == null && patch.bands() == null && patch.failMode() == null
                && patch.addAllowlist().isEmpty() && patch.removeAllowlist().isEmpty()
                && patch.addDenylist().isEmpty() && patch.removeDenylist().isEmpty();
        if (changesNothing) {
            errors.add("patch must change at least one supported policy field");
        }
        return List.copyOf(errors);
    }

    private void validateRateLimit(PolicyPatch.RateLimitPatch rate, List<String> errors) {
        if (rate == null) {
            return;
        }
        if (rate.algorithm() == null || !ALGORITHMS.contains(rate.algorithm())) {
            errors.add("rateLimit.algorithm is required and must be supported");
            return;
        }
        switch (rate.algorithm()) {
            case "FIXED_WINDOW", "SLIDING_WINDOW_COUNTER", "SLIDING_WINDOW_LOG" -> {
                positiveBound(errors, "rateLimit.limit", rate.limit(), 10_000_000);
                positiveBound(errors, "rateLimit.windowSeconds", rate.windowSeconds(), 86_400);
                if (rate.ratePerSecond() != null || rate.burst() != null) {
                    errors.add("window algorithms cannot set ratePerSecond or burst");
                }
            }
            case "TOKEN_BUCKET", "LEAKY_BUCKET" -> {
                if (rate.ratePerSecond() == null || rate.ratePerSecond() <= 0
                        || !Double.isFinite(rate.ratePerSecond())
                        || rate.ratePerSecond() > 1_000_000) {
                    errors.add("rateLimit.ratePerSecond must be a finite positive number no greater than 1000000");
                }
                positiveBound(errors, "rateLimit.burst", rate.burst(), 10_000_000);
                if (rate.limit() != null || rate.windowSeconds() != null) {
                    errors.add("bucket algorithms cannot set limit or windowSeconds");
                }
            }
            default -> { }
        }
    }

    private void validateBands(PolicyPatch.BandsPatch bands, List<String> errors) {
        if (bands == null) {
            return;
        }
        int observe = value(bands.observeMin(), defaults.defaultObserveMin());
        int soft = value(bands.softLimitMin(), defaults.defaultSoftLimitMin());
        int hard = value(bands.hardLimitMin(), defaults.defaultHardLimitMin());
        int challenge = value(bands.challengeMin(), defaults.defaultChallengeMin());
        int block = value(bands.blockMin(), defaults.defaultBlockMin());
        if (observe < 0 || block > 100 || !(observe < soft && soft < hard && hard < challenge && challenge < block)) {
            errors.add("resolved bands must be strictly increasing between 0 and 100");
        }
    }

    private static void validateCidrs(String field, List<String> values, List<String> errors) {
        if (values.size() > MAX_NETWORK_ENTRIES) {
            errors.add(field + " cannot contain more than " + MAX_NETWORK_ENTRIES + " entries");
        }
        for (String value : values) {
            if (value == null || value.length() > 64 || !isCidr(value)) {
                errors.add(field + " contains invalid CIDR: " + value);
            }
        }
        if (new HashSet<>(values).size() != values.size()) {
            errors.add(field + " contains duplicate entries");
        }
    }

    static boolean isCidr(String value) {
        String[] parts = value.split("/", -1);
        if (parts.length != 1 && parts.length != 2) {
            return false;
        }
        String addressText = parts[0];
        // Accept literal IPv4/IPv6 only. InetAddress.getByName would otherwise
        // perform DNS resolution on untrusted model output.
        boolean ipv4 = isIpv4Literal(addressText);
        boolean ipv6 = addressText.contains(":") && addressText.matches("[0-9A-Fa-f:]+");
        if (!ipv4 && !ipv6) {
            return false;
        }
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(addressText);
            int max = address.getAddress().length * 8;
            int prefix = parts.length == 1 ? max : Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= max;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isIpv4Literal(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}")) {
                return false;
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static void allowed(List<String> errors, String field, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            errors.add(field + " must be one of " + allowed);
        }
    }

    private static void positiveBound(List<String> errors, String field, Long value, long maximum) {
        if (value == null || value <= 0 || value > maximum) {
            errors.add(field + " must be positive and no greater than " + maximum);
        }
    }

    private static void rejectOverlap(List<String> errors, String message,
                                      List<String> left, List<String> right) {
        Set<String> overlap = new HashSet<>(left);
        overlap.retainAll(right);
        if (!overlap.isEmpty()) {
            errors.add(message);
        }
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
