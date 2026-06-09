package com.maluca.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.maluca.model.ClientState;
import com.maluca.model.RequestMeta;
import com.maluca.model.RiskSignals;
import com.maluca.model.UaClass;

class SignalsCollectorTest {

    private static final String CHROME_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36";

    private final SignalsCollector collector = new SignalsCollector();

    private static RequestMeta browserRequest() {
        return new RequestMeta("GET", "/api/products", CHROME_UA,
                "text/html,application/json", "en-US,en;q=0.9", "gzip, deflate, br",
                List.of("Host", "User-Agent", "Accept", "Accept-Language", "Accept-Encoding"), null);
    }

    private static RequestMeta bareBotRequest() {
        return new RequestMeta("GET", "/api/products", null, null, null, null, List.of("Host"), null);
    }

    @Test
    void mapsStateWindowsThrough() {
        ClientState state = new ClientState(5, 40, 3, 100, 7, 2, 1, "", -2);
        RiskSignals signals = collector.collect(browserRequest(), state, UaClass.BROWSER, false, false);

        assertThat(signals.burst10s()).isEqualTo(5);
        assertThat(signals.sustained60s()).isEqualTo(40);
        assertThat(signals.distinctPaths30s()).isEqualTo(7);
        assertThat(signals.sensitiveHits60s()).isEqualTo(2);
        assertThat(signals.fourxx60s()).isEqualTo(1);
    }

    @Test
    void browserRequestHasNoHeaderAnomalies() {
        RiskSignals signals = collector.collect(browserRequest(), ClientState.EMPTY, UaClass.BROWSER, false, false);

        assertThat(signals.headerAnomalies()).isZero();
        assertThat(signals.uaHeaderMismatch()).isFalse();
    }

    @Test
    void headerlessBotHasMaxAnomalies() {
        RiskSignals signals = collector.collect(bareBotRequest(), ClientState.EMPTY, UaClass.UNKNOWN, false, false);

        assertThat(signals.headerAnomalies()).isEqualTo(4);
        assertThat(signals.uaHeaderMismatch()).as("only browsers can mismatch").isFalse();
    }

    @Test
    void browserClaimWithMissingHeadersIsAMismatch() {
        // claims Chrome but sends none of the Accept-* headers a browser always sends
        RequestMeta fakeChrome = new RequestMeta("GET", "/x", CHROME_UA, null, null, null,
                List.of("Host", "User-Agent"), null);
        RiskSignals signals = collector.collect(fakeChrome, ClientState.EMPTY, UaClass.BROWSER, false, false);

        assertThat(signals.uaHeaderMismatch()).isTrue();
    }

    @Test
    void datacenterAndStickyAndLimitFlagsPropagate() {
        ClientState pinned = new ClientState(1, 1, 1, 1, 1, 0, 0, "HARD_LIMIT", 25);
        RiskSignals signals = collector.collect(browserRequest(), pinned, UaClass.BROWSER, true, true);

        assertThat(signals.priorEscalation()).isTrue();
        assertThat(signals.datacenter()).isTrue();
        assertThat(signals.limitExceeded()).isTrue();
    }
}
