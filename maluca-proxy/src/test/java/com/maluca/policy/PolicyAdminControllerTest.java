package com.maluca.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.maluca.TestFixtures;

class PolicyAdminControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void guardedListReturnsStructuredPolicySettings() {
        var properties = TestFixtures.defaultProperties();
        var controller = new PolicyAdminController(new PolicyRegistry(properties), properties);

        ResponseEntity<Object> response = controller.list("test-admin-token").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> policies = (List<Map<String, Object>>) body.get("policies");

        Map<String, Object> login = policyNamed(policies, "login");
        assertThat(login).containsEntry("route", "/login")
                .containsEntry("mode", "ENFORCE")
                .containsEntry("failMode", "FAIL_CLOSED")
                .containsKey("keying")
                .containsEntry("allowlist", List.of())
                .containsEntry("denylist", List.of());
        assertThat((Map<String, Object>) login.get("bands"))
                .containsEntry("blockMin", 80)
                .containsEntry("challengeMin", 60);
        assertThat((Map<String, Object>) login.get("rateLimit"))
                .containsEntry("algorithm", "SLIDING_WINDOW_LOG")
                .containsEntry("limit", 5L)
                .containsEntry("windowSeconds", 60L);

        Map<String, Object> api = policyNamed(policies, "api");
        assertThat(api).containsEntry("keying", "COMPOSITE");
    }

    @Test
    void listRetainsAdminTokenGuard() {
        var properties = TestFixtures.defaultProperties();
        var controller = new PolicyAdminController(new PolicyRegistry(properties), properties);

        assertThat(controller.list(null).block().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.list("wrong").block().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static Map<String, Object> policyNamed(List<Map<String, Object>> policies, String name) {
        return policies.stream()
                .filter(policy -> name.equals(policy.get("name")))
                .findFirst()
                .orElseThrow();
    }
}
