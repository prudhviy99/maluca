package com.maluca.triage.policy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.triage.config.TriageProperties;

/** CAS-guarded, atomic policy mutation with reload verification and rollback. */
@Service
public class PolicyFileService {

    private final YAMLMapper yaml = new YAMLMapper();
    private final Path policyFile;
    private final int backupRetention;
    private final RestClient proxy;

    public PolicyFileService(TriageProperties properties, RestClient malucaProxyClient) {
        this.policyFile = properties.policy().file().toAbsolutePath().normalize();
        this.backupRetention = properties.policy().backupRetention();
        this.proxy = malucaProxyClient;
    }

    public String sha256() {
        try {
            return sha256(Files.readAllBytes(readableFile()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read policy file " + policyFile, e);
        }
    }

    /** Computes the exact target content digest without mutating disk or proxy state. */
    public synchronized String targetSha256(PolicyPatch patch, String expectedSha256) {
        try {
            // Proposal review is valid in read-only/proposal-only deployments;
            // writability is required later, immediately before actual apply.
            byte[] original = Files.readAllBytes(readableFile());
            verifyExpectedSha(original, expectedSha256);
            return sha256(patchedDocument(original, patch));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare policy update", e);
        }
    }

    public synchronized ApplyResult apply(PolicyPatch patch, String expectedSha256) {
        Path backup = null;
        String originalSha = null;
        boolean fileMutated = false;
        try {
            Path file = writableFile();
            byte[] original = Files.readAllBytes(file);
            originalSha = verifyExpectedSha(original, expectedSha256);
            byte[] updated = patchedDocument(original, patch);

            backup = Files.createTempFile(
                    file.getParent(), file.getFileName() + ".bak.", "");
            Files.write(backup, original, StandardOpenOption.TRUNCATE_EXISTING);
            if (!constantTimeShaEquals(sha256(Files.readAllBytes(backup)), originalSha)) {
                throw new IllegalStateException("policy backup could not be verified before mutation");
            }
            atomicReplace(file, updated);
            fileMutated = true;
            reloadAndVerify(patch);
            pruneBackups(file);
            return new ApplyResult(originalSha, sha256(updated), backup.toString());
        } catch (Exception applyFailure) {
            if (fileMutated && backup != null && Files.exists(backup)) {
                try {
                    restoreBackup(backup, originalSha);
                } catch (Exception rollbackFailure) {
                    applyFailure.addSuppressed(rollbackFailure);
                    throw new PolicyApplyIndeterminateException(
                            "policy apply failed and rollback could not be verified", applyFailure);
                }
            } else if (backup != null) {
                // A failure while creating or verifying the pre-mutation backup
                // must not accumulate an empty/corrupt file in the retention set.
                try {
                    Files.deleteIfExists(backup);
                } catch (IOException cleanupFailure) {
                    applyFailure.addSuppressed(cleanupFailure);
                }
            }
            if (applyFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("policy apply failed", applyFailure);
        }
    }

    /** Compensates a previously verified apply after a later persistence failure. */
    public synchronized void rollback(ApplyResult result) {
        if (result == null) {
            throw new IllegalArgumentException("apply result is required for rollback");
        }
        Path backup = Path.of(result.backupPath()).toAbsolutePath().normalize();
        if (!policyFile.getParent().equals(backup.getParent())
                || !backup.getFileName().toString().startsWith(policyFile.getFileName() + ".bak.")) {
            throw new IllegalStateException("rollback backup is outside the policy backup namespace");
        }
        String current = sha256();
        if (!constantTimeShaEquals(current, result.appliedSha256())) {
            throw new IllegalStateException(
                    "active policy changed after apply; refusing an unsafe rollback");
        }
        restoreBackup(backup, result.previousSha256());
    }

    /** Reconciles an approved operation whose process may have stopped after mutation. */
    public synchronized void verifyApplied(PolicyPatch patch, String expectedTargetSha256) {
        if (!constantTimeShaEquals(sha256(), expectedTargetSha256)) {
            throw new IllegalStateException("policy file does not match the approved target digest");
        }
        reloadAndVerify(patch);
        if (!constantTimeShaEquals(sha256(), expectedTargetSha256)) {
            throw new IllegalStateException(
                    "policy file changed while the approved target was being verified");
        }
    }

    /**
     * Makes the proxy consume the exact current file and verifies the file did
     * not change across reload. Used only when reconciliation sees the recorded
     * pre-apply baseline digest.
     */
    public synchronized void verifyCurrentPolicy(String expectedSha256) {
        if (!constantTimeShaEquals(sha256(), expectedSha256)) {
            throw new IllegalStateException("policy file does not match the expected digest");
        }
        reload();
        if (!constantTimeShaEquals(sha256(), expectedSha256)) {
            throw new IllegalStateException("policy file changed while proxy state was being verified");
        }
    }

    private void restoreBackup(Path backup, String expectedPreviousSha256) {
        try {
            if (!Files.isRegularFile(backup) || !Files.isReadable(backup)) {
                throw new IllegalStateException("policy backup is unavailable: " + backup);
            }
            byte[] previous = Files.readAllBytes(backup);
            if (!constantTimeShaEquals(sha256(previous), expectedPreviousSha256)) {
                throw new IllegalStateException("policy backup digest does not match the pre-apply content");
            }
            atomicReplace(policyFile, previous);
            reload();
            if (!constantTimeShaEquals(sha256(), expectedPreviousSha256)) {
                throw new IllegalStateException("policy rollback could not be verified on disk");
            }
        } catch (IOException e) {
            throw new IllegalStateException("policy rollback failed", e);
        }
    }

    private byte[] patchedDocument(byte[] original, PolicyPatch patch) throws IOException {
        ObjectNode document = (ObjectNode) yaml.readTree(original);
        ObjectNode policy = findPolicy(document, patch.policyName(), patch.route());
        ObjectNode before = policy.deepCopy();
        applyPatch(policy, patch);
        if (policy.equals(before)) {
            throw new IllegalArgumentException("patch makes no effective policy change");
        }
        validateDocument(document);
        return yaml.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
    }

    private static String verifyExpectedSha(byte[] contents, String expectedSha256) {
        String actualSha = sha256(contents);
        if (!constantTimeShaEquals(actualSha, expectedSha256)) {
            throw new IllegalStateException("policy file changed since proposal; expected "
                    + expectedSha256 + " but found " + actualSha);
        }
        return actualSha;
    }

    private static boolean constantTimeShaEquals(String actual, String expected) {
        return actual != null && expected != null
                && MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private Path readableFile() {
        if (!Files.isRegularFile(policyFile)) {
            throw new IllegalStateException("policy file does not exist: " + policyFile);
        }
        if (!Files.isReadable(policyFile)) {
            throw new IllegalStateException("policy file must be readable: " + policyFile);
        }
        return policyFile;
    }

    private Path writableFile() {
        Path file = readableFile();
        if (!Files.isWritable(file) || !Files.isWritable(file.getParent())) {
            throw new IllegalStateException("policy file and parent directory must be writable: " + policyFile);
        }
        return file;
    }

    private static ObjectNode findPolicy(ObjectNode document, String name, String route) {
        JsonNode policies = document.get("policies");
        if (!(policies instanceof ArrayNode array)) {
            throw new IllegalArgumentException("policy document requires a policies array");
        }
        ObjectNode match = null;
        for (JsonNode node : array) {
            if (node instanceof ObjectNode object && name.equals(object.path("name").asText())
                    && route.equals(object.path("route").asText())) {
                if (match != null) {
                    throw new IllegalArgumentException("policy identity is duplicated: " + name);
                }
                match = object;
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("policy not found for name/route: " + name + " " + route);
        }
        return match;
    }

    private static void applyPatch(ObjectNode policy, PolicyPatch patch) {
        setText(policy, "mode", patch.mode());
        setText(policy, "keying", patch.keying());
        setText(policy, "fail-mode", patch.failMode());
        if (patch.rateLimit() != null) {
            ObjectNode rate = policy.putObject("rate-limit");
            setText(rate, "algorithm", patch.rateLimit().algorithm());
            setLong(rate, "limit", patch.rateLimit().limit());
            setLong(rate, "window-seconds", patch.rateLimit().windowSeconds());
            setDouble(rate, "rate-per-second", patch.rateLimit().ratePerSecond());
            setLong(rate, "burst", patch.rateLimit().burst());
        }
        if (patch.bands() != null) {
            ObjectNode bands = policy.withObject("bands");
            setInt(bands, "observe-min", patch.bands().observeMin());
            setInt(bands, "soft-limit-min", patch.bands().softLimitMin());
            setInt(bands, "hard-limit-min", patch.bands().hardLimitMin());
            setInt(bands, "challenge-min", patch.bands().challengeMin());
            setInt(bands, "block-min", patch.bands().blockMin());
        }
        mutateSet(policy, "allowlist", patch.addAllowlist(), patch.removeAllowlist());
        mutateSet(policy, "denylist", patch.addDenylist(), patch.removeDenylist());
    }

    private static void validateDocument(ObjectNode document) {
        JsonNode policies = document.get("policies");
        if (!(policies instanceof ArrayNode array) || array.isEmpty()) {
            throw new IllegalArgumentException("policy document must contain policies");
        }
        Set<String> names = new HashSet<>();
        var parser = new org.springframework.web.util.pattern.PathPatternParser();
        for (JsonNode node : array) {
            String name = node.path("name").asText("");
            String route = node.path("route").asText("");
            if (name.isBlank() || !names.add(name)) {
                throw new IllegalArgumentException("policy names must be non-empty and unique: " + name);
            }
            parser.parse(route);
            JsonNode bands = node.get("bands");
            if (bands != null) {
                List<Integer> present = new ArrayList<>();
                for (String key : List.of("observe-min", "soft-limit-min", "hard-limit-min",
                        "challenge-min", "block-min")) {
                    if (bands.has(key)) {
                        int value = bands.get(key).asInt(-1);
                        if (value < 0 || value > 100) {
                            throw new IllegalArgumentException("band out of range for policy " + name);
                        }
                        present.add(value);
                    }
                }
                for (int i = 1; i < present.size(); i++) {
                    if (present.get(i - 1) >= present.get(i)) {
                        throw new IllegalArgumentException("bands are not increasing for policy " + name);
                    }
                }
            }
            validateNetworkSeparation(node, name);
        }
    }

    /**
     * The proxy checks allowlist before denylist, so any overlap would silently
     * weaken a reviewed deny rule. Compare canonical network ranges rather than
     * raw strings so aliases such as 192.0.2.7/24 and 192.0.2.0/24 cannot evade
     * the final-state check.
     */
    private static void validateNetworkSeparation(JsonNode policy, String policyName) {
        List<NetworkRange> allow = networkRanges(policy.get("allowlist"), "allowlist", policyName);
        List<NetworkRange> deny = networkRanges(policy.get("denylist"), "denylist", policyName);
        for (NetworkRange allowed : allow) {
            for (NetworkRange denied : deny) {
                if (allowed.overlaps(denied)) {
                    throw new IllegalArgumentException(
                            "allowlist and denylist overlap for policy " + policyName
                                    + ": " + allowed.source() + " / " + denied.source());
                }
            }
        }
    }

    private static List<NetworkRange> networkRanges(
            JsonNode value, String field, String policyName) {
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array for policy " + policyName);
        }
        List<NetworkRange> ranges = new ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual()) {
                throw new IllegalArgumentException(field + " entries must be strings for policy " + policyName);
            }
            ranges.add(NetworkRange.parse(entry.textValue(), field, policyName));
        }
        for (int left = 0; left < ranges.size(); left++) {
            for (int right = left + 1; right < ranges.size(); right++) {
                if (ranges.get(left).sameRange(ranges.get(right))) {
                    throw new IllegalArgumentException(
                            field + " contains duplicate canonical networks for policy " + policyName);
                }
            }
        }
        return List.copyOf(ranges);
    }

    private void reloadAndVerify(PolicyPatch patch) {
        reload();
        JsonNode response = proxy.get().uri("/_maluca/admin/policies")
                .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
        if (response == null || !activePolicyMatches(response.path("policies"), patch)) {
            throw new IllegalStateException("proxy reload did not expose the proposed policy values");
        }
    }

    private void reload() {
        JsonNode result = proxy.post().uri("/_maluca/admin/policies/reload")
                .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
        if (result == null || !result.path("reloaded").asBoolean(false)) {
            throw new IllegalStateException("proxy rejected policy reload");
        }
    }

    private static boolean activePolicyMatches(JsonNode policies, PolicyPatch patch) {
        if (!policies.isArray()) {
            return false;
        }
        for (JsonNode policy : policies) {
            if (patch.policyName().equals(policy.path("name").asText())
                    && patch.route().equals(policy.path("route").asText())) {
                if (patch.mode() != null && !patch.mode().equals(policy.path("mode").asText())) return false;
                if (patch.keying() != null && !patch.keying().equals(policy.path("keying").asText())) return false;
                if (patch.failMode() != null && !patch.failMode().equals(policy.path("failMode").asText())) return false;
                if (patch.rateLimit() != null
                        && !rateLimitMatches(policy.path("rateLimit"), patch.rateLimit())) return false;
                if (patch.bands() != null && !bandsMatch(policy.path("bands"), patch.bands())) return false;
                if (!containsAll(policy.path("allowlist"), patch.addAllowlist())) return false;
                if (!containsNone(policy.path("allowlist"), patch.removeAllowlist())) return false;
                if (!containsAll(policy.path("denylist"), patch.addDenylist())) return false;
                if (!containsNone(policy.path("denylist"), patch.removeDenylist())) return false;
                return true;
            }
        }
        return false;
    }

    private static boolean bandsMatch(JsonNode node, PolicyPatch.BandsPatch bands) {
        return matches(node, "observeMin", bands.observeMin())
                && matches(node, "softLimitMin", bands.softLimitMin())
                && matches(node, "hardLimitMin", bands.hardLimitMin())
                && matches(node, "challengeMin", bands.challengeMin())
                && matches(node, "blockMin", bands.blockMin());
    }

    private static boolean rateLimitMatches(JsonNode node, PolicyPatch.RateLimitPatch rate) {
        return rate.algorithm().equals(node.path("algorithm").asText())
                && matches(node, "limit", rate.limit())
                && matches(node, "windowSeconds", rate.windowSeconds())
                && matches(node, "ratePerSecond", rate.ratePerSecond())
                && matches(node, "burst", rate.burst());
    }

    private static boolean matches(JsonNode node, String key, Integer expected) {
        return expected == null || node.path(key).asInt(Integer.MIN_VALUE) == expected;
    }

    private static boolean matches(JsonNode node, String key, Long expected) {
        return expected == null || node.path(key).asLong(Long.MIN_VALUE) == expected;
    }

    private static boolean matches(JsonNode node, String key, Double expected) {
        return expected == null
                || Double.compare(node.path(key).asDouble(Double.NaN), expected) == 0;
    }

    private static boolean containsAll(JsonNode array, List<String> values) {
        Set<String> present = new HashSet<>();
        array.forEach(value -> present.add(value.asText()));
        return present.containsAll(values);
    }

    private static boolean containsNone(JsonNode array, List<String> values) {
        Set<String> present = new HashSet<>();
        array.forEach(value -> present.add(value.asText()));
        return values.stream().noneMatch(present::contains);
    }

    private static void mutateSet(ObjectNode policy, String field, List<String> additions, List<String> removals) {
        if (additions.isEmpty() && removals.isEmpty()) {
            return;
        }
        Set<String> values = new java.util.LinkedHashSet<>();
        JsonNode existing = policy.get(field);
        if (existing != null && existing.isArray()) {
            existing.forEach(node -> values.add(node.asText()));
        }
        for (String removal : removals) {
            if (!values.remove(removal)) {
                throw new IllegalArgumentException(
                        field + " removal does not match an active entry: " + removal);
            }
        }
        for (String addition : additions) {
            if (!values.add(addition)) {
                throw new IllegalArgumentException(
                        field + " addition is already active: " + addition);
            }
        }
        ArrayNode result = policy.putArray(field);
        values.forEach(result::add);
    }

    private record NetworkRange(byte[] network, int prefixBits, String source) {

        static NetworkRange parse(String source, String field, String policyName) {
            if (source == null || source.isBlank()) {
                throw invalid(field, policyName, source);
            }
            String[] parts = source.split("/", -1);
            if (parts.length < 1 || parts.length > 2 || !isLiteralIp(parts[0])) {
                throw invalid(field, policyName, source);
            }
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length == 1
                        ? address.length * 8 : Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.length * 8) {
                    throw invalid(field, policyName, source);
                }
                byte[] network = address.clone();
                for (int bit = prefix; bit < network.length * 8; bit++) {
                    network[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
                }
                return new NetworkRange(network, prefix, source);
            } catch (UnknownHostException | NumberFormatException failure) {
                throw invalid(field, policyName, source);
            }
        }

        boolean overlaps(NetworkRange other) {
            if (network.length != other.network.length) {
                return false;
            }
            int commonBits = Math.min(prefixBits, other.prefixBits);
            for (int bit = 0; bit < commonBits; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((network[bit / 8] & mask) != (other.network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }

        boolean sameRange(NetworkRange other) {
            return prefixBits == other.prefixBits && java.util.Arrays.equals(network, other.network);
        }

        private static IllegalArgumentException invalid(
                String field, String policyName, String source) {
            return new IllegalArgumentException(
                    field + " contains invalid CIDR for policy " + policyName + ": " + source);
        }

        private static boolean isLiteralIp(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            if (value.contains(":")) {
                return value.matches("[0-9A-Fa-f:]+");
            }
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void setText(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }
    private static void setLong(ObjectNode node, String field, Long value) {
        if (value != null) node.put(field, value);
    }
    private static void setInt(ObjectNode node, String field, Integer value) {
        if (value != null) node.put(field, value);
    }
    private static void setDouble(ObjectNode node, String field, Double value) {
        if (value != null) node.put(field, value);
    }

    private static void atomicReplace(Path target, byte[] contents) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, contents, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("policy filesystem does not support atomic replacement", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void pruneBackups(Path file) throws IOException {
        if (backupRetention < 1) return;
        String prefix = file.getFileName() + ".bak.";
        try (var paths = Files.list(file.getParent())) {
            List<Path> backups = paths.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparingLong(PolicyFileService::modified).reversed()).toList();
            for (int i = backupRetention; i < backups.size(); i++) {
                Files.deleteIfExists(backups.get(i));
            }
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException e) { return Long.MIN_VALUE; }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ApplyResult(String previousSha256, String appliedSha256, String backupPath) {
    }

    public static final class PolicyApplyIndeterminateException extends IllegalStateException {
        public PolicyApplyIndeterminateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
