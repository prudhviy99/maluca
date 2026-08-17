package com.maluca.mcp.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.mcp.config.MalucaMcpProperties;

@Component
public class PolicyPatchValidator {

    private static final Pattern POLICY_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern ROUTE = Pattern.compile("/[A-Za-z0-9_./*{}?\\-]{0,255}");
    private static final Pattern SHA256 = Pattern.compile("[0-9A-Fa-f]{64}");
    private static final Set<String> MODES = Set.of("ENFORCE", "OBSERVE", "DRY_RUN");
    private static final Set<String> KEYING = Set.of("NETWORK", "COMPOSITE", "FINGERPRINT");
    private static final Set<String> FAIL_MODES = Set.of("FAIL_OPEN", "FAIL_CLOSED");
    private static final Set<String> ALGORITHMS = Set.of(
            "FIXED_WINDOW", "SLIDING_WINDOW_COUNTER", "SLIDING_WINDOW_LOG",
            "TOKEN_BUCKET", "LEAKY_BUCKET");

    private final MalucaMcpProperties.Limits limits;

    public PolicyPatchValidator(MalucaMcpProperties properties) {
        this.limits = properties.limits();
    }

    public void validate(PolicyPatch patch) {
        if (patch == null) {
            throw new ToolInputException("patch is required");
        }
        if (patch.policyName() == null || !POLICY_NAME.matcher(patch.policyName()).matches()) {
            throw new ToolInputException("patch.policyName must be a simple policy identifier");
        }
        if (patch.route() == null || !ROUTE.matcher(patch.route()).matches() || patch.route().contains("..")) {
            throw new ToolInputException("patch.route must be a bounded absolute route pattern");
        }
        validateEnum("patch.mode", patch.mode(), MODES);
        validateEnum("patch.keying", patch.keying(), KEYING);
        validateEnum("patch.failMode", patch.failMode(), FAIL_MODES);
        if (patch.rationale() == null || patch.rationale().isBlank()) {
            throw new ToolInputException("patch.rationale is required");
        }
        if (patch.rationale().length() > limits.maxRationaleCharacters()
                || patch.rationale().chars().anyMatch(Character::isISOControl)) {
            throw new ToolInputException("patch.rationale is invalid or too long");
        }
        validateRateLimit(patch.rateLimit());
        validateBands(patch.bands());
        validateNetworks("addAllowlist", patch.addAllowlist());
        validateNetworks("removeAllowlist", patch.removeAllowlist());
        validateNetworks("addDenylist", patch.addDenylist());
        validateNetworks("removeDenylist", patch.removeDenylist());
        int totalEntries = patch.addAllowlist().size() + patch.removeAllowlist().size()
                + patch.addDenylist().size() + patch.removeDenylist().size();
        if (totalEntries > limits.maxPatchEntries()) {
            throw new ToolInputException("patch contains too many network entries");
        }
        rejectOverlap("patch cannot add and remove the same allowlist entry",
                patch.addAllowlist(), patch.removeAllowlist());
        rejectOverlap("patch cannot add and remove the same denylist entry",
                patch.addDenylist(), patch.removeDenylist());
        rejectOverlap("patch cannot add the same entry to the allowlist and denylist",
                patch.addAllowlist(), patch.addDenylist());
        boolean changesNothing = patch.mode() == null && patch.keying() == null
                && patch.rateLimit() == null && patch.bands() == null && patch.failMode() == null
                && patch.addAllowlist().isEmpty() && patch.removeAllowlist().isEmpty()
                && patch.addDenylist().isEmpty() && patch.removeDenylist().isEmpty();
        if (changesNothing) {
            throw new ToolInputException("patch must change at least one supported policy field");
        }
    }

    public void validateApproval(ApprovalRequest request) {
        if (request == null) {
            throw new ToolInputException("approval is required");
        }
        if (request.proposalId() == null) {
            throw new ToolInputException("proposalId is required");
        }
        if (request.expectedProposalSha256() == null
                || !SHA256.matcher(request.expectedProposalSha256()).matches()) {
            throw new ToolInputException(
                    "expectedProposalSha256 must be a 64-character SHA-256 hex digest");
        }
        if (request.expectedPolicySha256() == null
                || !SHA256.matcher(request.expectedPolicySha256()).matches()) {
            throw new ToolInputException("expectedPolicySha256 must be a 64-character SHA-256 hex digest");
        }
        if (request.expectedIncidentVersion() < 0) {
            throw new ToolInputException("expectedIncidentVersion cannot be negative");
        }
        if (request.approvedBy() == null || request.approvedBy().isBlank()
                || request.approvedBy().length() > 128
                || request.approvedBy().chars().anyMatch(Character::isISOControl)) {
            throw new ToolInputException("approvedBy is required and must be at most 128 characters");
        }
    }

    private void validateRateLimit(PolicyPatch.RateLimitPatch rateLimit) {
        if (rateLimit == null) {
            return;
        }
        validateEnum("patch.rateLimit.algorithm", rateLimit.algorithm(), ALGORITHMS);
        if (rateLimit.algorithm() == null) {
            throw new ToolInputException("patch.rateLimit.algorithm is required");
        }
        positiveBound("patch.rateLimit.limit", rateLimit.limit(), 10_000_000L);
        positiveBound("patch.rateLimit.windowSeconds", rateLimit.windowSeconds(), 86_400L);
        positiveBound("patch.rateLimit.burst", rateLimit.burst(), 10_000_000L);
        if (rateLimit.ratePerSecond() != null
                && (!(rateLimit.ratePerSecond() > 0) || !Double.isFinite(rateLimit.ratePerSecond())
                        || rateLimit.ratePerSecond() > 1_000_000)) {
            throw new ToolInputException("patch.rateLimit.ratePerSecond is outside the safe range");
        }
        switch (rateLimit.algorithm()) {
            case "FIXED_WINDOW", "SLIDING_WINDOW_COUNTER", "SLIDING_WINDOW_LOG" -> {
                if (rateLimit.limit() == null || rateLimit.windowSeconds() == null) {
                    throw new ToolInputException(
                            "window algorithms require limit and windowSeconds");
                }
                if (rateLimit.ratePerSecond() != null || rateLimit.burst() != null) {
                    throw new ToolInputException(
                            "window algorithms cannot set ratePerSecond or burst");
                }
            }
            case "TOKEN_BUCKET", "LEAKY_BUCKET" -> {
                if (rateLimit.ratePerSecond() == null || rateLimit.burst() == null) {
                    throw new ToolInputException(
                            "bucket algorithms require ratePerSecond and burst");
                }
                if (rateLimit.limit() != null || rateLimit.windowSeconds() != null) {
                    throw new ToolInputException(
                            "bucket algorithms cannot set limit or windowSeconds");
                }
            }
            default -> { }
        }
    }

    private static void positiveBound(String name, Long value, long max) {
        if (value != null && (value < 1 || value > max)) {
            throw new ToolInputException(name + " is outside the safe range");
        }
    }

    private static void validateBands(PolicyPatch.BandsPatch bands) {
        if (bands == null) {
            return;
        }
        List<Integer> values = List.of(
                sentinel(bands.observeMin()), sentinel(bands.softLimitMin()),
                sentinel(bands.hardLimitMin()), sentinel(bands.challengeMin()),
                sentinel(bands.blockMin()));
        Integer previous = null;
        for (Integer value : values) {
            if (value == Integer.MIN_VALUE) {
                continue;
            }
            if (value < 0 || value > 100) {
                throw new ToolInputException("patch band thresholds must be between 0 and 100");
            }
            if (previous != null && value <= previous) {
                throw new ToolInputException("specified patch band thresholds must increase in severity order");
            }
            previous = value;
        }
    }

    private static int sentinel(Integer value) {
        return value == null ? Integer.MIN_VALUE : value;
    }

    private void validateNetworks(String name, List<String> entries) {
        if (entries.size() > limits.maxPatchEntries()) {
            throw new ToolInputException("patch." + name + " contains too many entries");
        }
        for (String entry : entries) {
            if (!isIpOrCidr(entry)) {
                throw new ToolInputException("patch." + name + " contains an invalid IP/CIDR entry");
            }
        }
        if (new HashSet<>(entries).size() != entries.size()) {
            throw new ToolInputException("patch." + name + " contains duplicate entries");
        }
    }

    private static boolean isIpOrCidr(String entry) {
        if (entry == null || entry.isBlank() || entry.length() > 64) {
            return false;
        }
        String[] parts = entry.split("/", -1);
        if (parts.length > 2 || parts[0].isBlank()) {
            return false;
        }
        int bits;
        if (parts[0].contains(":")) {
            if (!parts[0].matches("[0-9A-Fa-f:.]+")) {
                return false;
            }
            try {
                InetAddress address = InetAddress.getByName(parts[0]);
                if (!(address instanceof Inet6Address)) {
                    return false;
                }
            } catch (UnknownHostException exception) {
                return false;
            }
            bits = 128;
        } else {
            String[] octets = parts[0].split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                try {
                    if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                        return false;
                    }
                } catch (NumberFormatException exception) {
                    return false;
                }
            }
            bits = 32;
        }
        if (parts.length == 1) {
            return true;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= bits;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static void rejectOverlap(String message, List<String> left, List<String> right) {
        Set<String> overlap = new HashSet<>(left);
        overlap.retainAll(right);
        if (!overlap.isEmpty()) {
            throw new ToolInputException(message);
        }
    }

    private static void validateEnum(String name, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value.toUpperCase(Locale.ROOT))) {
            throw new ToolInputException(name + " is unsupported");
        }
    }
}
