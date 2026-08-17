package com.maluca.triage.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.triage.TriageTestFixtures;

class PolicyFileServiceTest {

    @TempDir
    Path temporary;

    @Test
    void atomicallyAppliesReloadsVerifiesAndKeepsBackup() throws Exception {
        Path file = writePolicy();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://proxy")
                .defaultHeader("X-Maluca-Admin-Token", "admin");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"reloaded\":true}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://proxy/_maluca/admin/policies"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"policies":[{"name":"api","route":"/api/**","mode":"DRY_RUN",
                         "keying":"COMPOSITE","bands":{},"rateLimit":{},"allowlist":[],
                         "denylist":[],"failMode":"FAIL_OPEN"}]}
                        """, MediaType.APPLICATION_JSON));
        var service = new PolicyFileService(TriageTestFixtures.properties(file), builder.build());
        String before = service.sha256();

        var result = service.apply(modePatch(), before);

        assertThat(result.previousSha256()).isEqualTo(before);
        assertThat(result.appliedSha256()).isNotEqualTo(before);
        assertThat(Files.readString(file)).contains("mode: \"DRY_RUN\"");
        assertThat(Path.of(result.backupPath())).exists();
        server.verify();
    }

    @Test
    void refusesStaleContentHashBeforeCallingProxy() throws Exception {
        Path file = writePolicy();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://proxy");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var service = new PolicyFileService(TriageTestFixtures.properties(file), builder.build());

        assertThatThrownBy(() -> service.apply(modePatch(), "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed since proposal");
        server.verify();
    }

    @Test
    void restoresBackupWhenVerificationFails() throws Exception {
        Path file = writePolicy();
        byte[] original = Files.readAllBytes(file);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://proxy");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andRespond(withSuccess("{\"reloaded\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://proxy/_maluca/admin/policies"))
                .andRespond(withSuccess("{\"policies\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andRespond(withSuccess("{\"reloaded\":true}", MediaType.APPLICATION_JSON));
        var service = new PolicyFileService(TriageTestFixtures.properties(file), builder.build());

        assertThatThrownBy(() -> service.apply(modePatch(), service.sha256()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not expose");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        server.verify();
    }

    @Test
    void contentHashOnlyRequiresAReadablePolicyFile() throws Exception {
        Path file = writePolicy();
        var service = new PolicyFileService(TriageTestFixtures.properties(file),
                RestClient.builder().baseUrl("http://proxy").build());

        assertThat(service.sha256()).hasSize(64);
    }

    @Test
    void computesApprovedTargetDigestWithoutMutatingThePolicy() throws Exception {
        Path file = writePolicy();
        byte[] original = Files.readAllBytes(file);
        var service = new PolicyFileService(TriageTestFixtures.properties(file),
                RestClient.builder().baseUrl("http://proxy").build());

        String target = service.targetSha256(modePatch(), service.sha256());

        assertThat(target).hasSize(64).isNotEqualTo(service.sha256());
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    void rejectsFinalAllowDenyOverlapUsingCanonicalNetworkRanges() throws Exception {
        Path file = writePolicyWithNetworks();
        var service = new PolicyFileService(TriageTestFixtures.properties(file),
                RestClient.builder().baseUrl("http://proxy").build());
        PolicyPatch patch = new PolicyPatch("api", "/api/**", null, null, null, null,
                List.of(), List.of(), List.of("192.0.2.128/25"), List.of(), null,
                "deny a suspicious subnet");

        assertThatThrownBy(() -> service.targetSha256(patch, service.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist and denylist overlap")
                .hasMessageContaining("192.0.2.7/24")
                .hasMessageContaining("192.0.2.128/25");
    }

    @Test
    void requiresNetworkSetOperationsToChangeTheResolvedPolicy() throws Exception {
        Path file = writePolicyWithNetworks();
        var service = new PolicyFileService(TriageTestFixtures.properties(file),
                RestClient.builder().baseUrl("http://proxy").build());
        PolicyPatch missingRemoval = new PolicyPatch("api", "/api/**", null, null, null, null,
                List.of(), List.of("198.51.100.0/24"), List.of(), List.of(), null,
                "remove a reviewed exception");
        PolicyPatch duplicateAddition = new PolicyPatch("api", "/api/**", null, null, null, null,
                List.of("192.0.2.7/24"), List.of(), List.of(), List.of(), null,
                "add a reviewed exception");

        assertThatThrownBy(() -> service.targetSha256(missingRemoval, service.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("removal does not match an active entry");
        assertThatThrownBy(() -> service.targetSha256(duplicateAddition, service.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("addition is already active");
    }

    @Test
    void rejectsAFieldAssignmentThatAlreadyMatchesTheLivePolicy() throws Exception {
        Path file = writePolicy();
        var service = new PolicyFileService(TriageTestFixtures.properties(file),
                RestClient.builder().baseUrl("http://proxy").build());
        PolicyPatch noOp = new PolicyPatch("api", "/api/**", "ENFORCE", null,
                null, null, List.of(), List.of(), List.of(), List.of(), null,
                "keep the already active mode");

        assertThatThrownBy(() -> service.targetSha256(noOp, service.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no effective policy change");
    }

    @Test
    void permitsAnExplicitAllowToDenyTransferWithoutFinalOverlap() throws Exception {
        Path file = writePolicyWithNetworks();
        var service = new PolicyFileService(TriageTestFixtures.properties(file),
                RestClient.builder().baseUrl("http://proxy").build());
        PolicyPatch patch = new PolicyPatch("api", "/api/**", null, null, null, null,
                List.of(), List.of("192.0.2.7/24"), List.of("192.0.2.0/24"), List.of(), null,
                "replace the exception with an explicit denial");

        assertThat(service.targetSha256(patch, service.sha256())).hasSize(64);
    }

    @Test
    void compensatesACompletedApplyWhenLaterPersistenceFails() throws Exception {
        Path file = writePolicy();
        byte[] original = Files.readAllBytes(file);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://proxy");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andRespond(withSuccess("{\"reloaded\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://proxy/_maluca/admin/policies"))
                .andRespond(withSuccess("""
                        {"policies":[{"name":"api","route":"/api/**","mode":"DRY_RUN",
                         "keying":"COMPOSITE","bands":{},"rateLimit":{},"allowlist":[],
                         "denylist":[],"failMode":"FAIL_OPEN"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andRespond(withSuccess("{\"reloaded\":true}", MediaType.APPLICATION_JSON));
        var service = new PolicyFileService(TriageTestFixtures.properties(file), builder.build());

        var result = service.apply(modePatch(), service.sha256());
        service.rollback(result);

        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(service.sha256()).isEqualTo(result.previousSha256());
        server.verify();
    }

    @Test
    void reportsIndeterminateWhenApplyAndRollbackVerificationBothFail() throws Exception {
        Path file = writePolicy();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://proxy");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andRespond(withSuccess("{\"reloaded\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://proxy/_maluca/admin/policies"))
                .andRespond(withSuccess("{\"policies\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://proxy/_maluca/admin/policies/reload"))
                .andRespond(withSuccess("{\"reloaded\":false}", MediaType.APPLICATION_JSON));
        var service = new PolicyFileService(TriageTestFixtures.properties(file), builder.build());

        assertThatThrownBy(() -> service.apply(modePatch(), service.sha256()))
                .isInstanceOf(PolicyFileService.PolicyApplyIndeterminateException.class)
                .hasMessageContaining("rollback could not be verified");
        server.verify();
    }

    private Path writePolicy() throws Exception {
        Path file = temporary.resolve("policies.yml");
        Files.writeString(file, """
                policies:
                  - name: api
                    route: /api/**
                    mode: ENFORCE
                    keying: COMPOSITE
                    fail-mode: FAIL_OPEN
                """, StandardCharsets.UTF_8);
        return file;
    }

    private Path writePolicyWithNetworks() throws Exception {
        Path file = temporary.resolve("policies-with-networks.yml");
        Files.writeString(file, """
                policies:
                  - name: api
                    route: /api/**
                    mode: ENFORCE
                    allowlist:
                      - 192.0.2.7/24
                    denylist: []
                """, StandardCharsets.UTF_8);
        return file;
    }

    private static PolicyPatch modePatch() {
        return new PolicyPatch("api", "/api/**", "DRY_RUN", null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, "stage safely");
    }
}
