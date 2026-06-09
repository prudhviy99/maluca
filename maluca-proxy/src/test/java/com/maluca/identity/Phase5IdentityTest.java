package com.maluca.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.maluca.TestFixtures;
import com.maluca.config.MalucaProperties.Identity.KeyStrategy;
import com.maluca.model.ClientIdentity;
import com.maluca.model.RequestMeta;
import com.maluca.model.UaClass;

/** Fingerprinting, datacenter detection, UA table, FCrDNS logic — all pure or fake-backed. */
class Phase5IdentityTest {

    private static final String CHROME_UA =
            "Mozilla/5.0 (Macintosh) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36";

    private static RequestMeta meta(String ua, String ja3) {
        return new RequestMeta("GET", "/x", ua, "text/html", "en-US", "gzip",
                List.of("Host", "User-Agent", "Accept"), ja3);
    }

    // ── Fingerprint ───────────────────────────────────────────────────────────

    @Test
    void fingerprintIsStableForIdenticalShape() {
        FingerprintService service = new FingerprintService();
        assertThat(service.fingerprint(meta(CHROME_UA, null)))
                .isEqualTo(service.fingerprint(meta(CHROME_UA, null)));
    }

    @Test
    void fingerprintChangesWithUaAndJa3() {
        FingerprintService service = new FingerprintService();
        String base = service.fingerprint(meta(CHROME_UA, null));

        assertThat(service.fingerprint(meta("curl/8.0", null))).isNotEqualTo(base);
        assertThat(service.fingerprint(meta(CHROME_UA, "771,4865-4866,23-65281"))).isNotEqualTo(base);
    }

    @Test
    void compositeStrategyCombinesLayers_fingerprintStrategySurvivesIpRotation() {
        ClientIdentity a = ClientIdentity.ofIp("1.1.1.1").withKeys("sess", "fp123", KeyStrategy.COMPOSITE);
        assertThat(a.compositeKey()).isEqualTo("1.1.1.1|sess|fp123");

        ClientIdentity b1 = ClientIdentity.ofIp("1.1.1.1").withKeys(null, "fp123", KeyStrategy.FINGERPRINT);
        ClientIdentity b2 = ClientIdentity.ofIp("2.2.2.2").withKeys(null, "fp123", KeyStrategy.FINGERPRINT);
        assertThat(b1.compositeKey()).isEqualTo(b2.compositeKey());
    }

    // ── Datacenter CIDRs ──────────────────────────────────────────────────────

    @Test
    void datacenterDetectorMatchesCidrs() {
        DatacenterDetector detector = new DatacenterDetector(TestFixtures.defaultProperties());

        assertThat(detector.isDatacenter("159.65.10.20")).isTrue();   // DO /16
        assertThat(detector.isDatacenter("3.120.4.5")).isTrue();      // AWS /9
        assertThat(detector.isDatacenter("159.66.0.1")).isFalse();    // just outside /16
        assertThat(detector.isDatacenter("203.0.113.7")).isFalse();   // residential-ish
        assertThat(detector.isDatacenter("not-an-ip")).isFalse();
    }

    // ── UA classification from YAML ───────────────────────────────────────────

    @Test
    void uaTableClassifies() {
        UaClassifier classifier = new UaClassifier();

        assertThat(classifier.classify("Mozilla/5.0 (compatible; Googlebot/2.1)"))
                .isEqualTo(UaClass.VERIFIED_BOT);
        assertThat(classifier.classify("sqlmap/1.7")).isEqualTo(UaClass.KNOWN_BAD_BOT);
        assertThat(classifier.classify("curl/8.4.0")).isEqualTo(UaClass.SCRIPT_CLIENT);
        assertThat(classifier.classify(CHROME_UA)).isEqualTo(UaClass.BROWSER);
        assertThat(classifier.classify(null)).isEqualTo(UaClass.UNKNOWN);
        assertThat(classifier.classify("WeirdAgent/1.0")).isEqualTo(UaClass.UNKNOWN);
    }

    // ── FCrDNS with a fake resolver ───────────────────────────────────────────

    private static class FakeResolver implements VerifiedBotService.DnsResolver {
        final Map<String, String> ptr;
        final Map<String, List<String>> forward;

        FakeResolver(Map<String, String> ptr, Map<String, List<String>> forward) {
            this.ptr = ptr;
            this.forward = forward;
        }

        @Override
        public String reverse(String ip) {
            return ptr.get(ip);
        }

        @Override
        public List<String> forward(String hostname) {
            return forward.getOrDefault(hostname, List.of());
        }
    }

    @Test
    void genuineGooglebotPassesFcrdns() {
        var resolver = new FakeResolver(
                Map.of("66.249.66.1", "crawl-66-249-66-1.googlebot.com"),
                Map.of("crawl-66-249-66-1.googlebot.com", List.of("66.249.66.1")));
        var service = new VerifiedBotService(null, resolver);

        assertThat(service.verify("66.249.66.1").block(Duration.ofSeconds(2))).isTrue();
    }

    @Test
    void spooferWithFakePtrFailsForwardConfirmation() {
        // attacker controls their own PTR ("googlebot.com" suffix) but cannot
        // make Google's zone resolve that name back to their IP
        var resolver = new FakeResolver(
                Map.of("6.6.6.6", "crawl-fake.googlebot.com"),
                Map.of("crawl-fake.googlebot.com", List.of("66.249.66.1")));
        var service = new VerifiedBotService(null, resolver);

        assertThat(service.verify("6.6.6.6").block(Duration.ofSeconds(2))).isFalse();
    }

    @Test
    void spooferWithUnrelatedPtrFails() {
        var resolver = new FakeResolver(
                Map.of("6.6.6.6", "evil.example.com"),
                Map.of("evil.example.com", List.of("6.6.6.6")));
        var service = new VerifiedBotService(null, resolver);

        assertThat(service.verify("6.6.6.6").block(Duration.ofSeconds(2))).isFalse();
    }

    @Test
    void missingPtrFails() {
        var service = new VerifiedBotService(null, new FakeResolver(Map.of(), Map.of()));

        assertThat(service.verify("6.6.6.6").block(Duration.ofSeconds(2))).isFalse();
    }
}
