package com.maluca.scoring;

import org.springframework.stereotype.Component;

import com.maluca.model.ClientState;
import com.maluca.model.RequestMeta;
import com.maluca.model.RiskSignals;
import com.maluca.model.UaClass;

/**
 * Pure function: (request, state, resolved facts) → signals. No I/O happens
 * here — Redis state, UA class resolution (which may involve FCrDNS), and
 * datacenter lookup all happen beforehand — which is what makes the whole
 * scoring pipeline unit-testable without infrastructure.
 */
@Component
public class SignalsCollector {

    public RiskSignals collect(RequestMeta request, ClientState state,
                               UaClass uaClass, boolean datacenter, boolean limitExceeded) {
        int anomalies = countHeaderAnomalies(request);
        return RiskSignals.builder()
                .burst10s(state.countLast10s())
                .sustained60s(state.countLast60s())
                .distinctPaths30s(state.distinctPaths30s())
                .sensitiveHits60s(state.sensitiveHits60s())
                .fourxx60s(state.fourxxLast60s())
                .headerAnomalies(anomalies)
                .uaClass(uaClass)
                // claims to be a real browser but is missing headers real
                // browsers always send — stronger than either fact alone
                .uaHeaderMismatch((uaClass == UaClass.BROWSER || uaClass == UaClass.MOBILE_APP)
                        && anomalies > 0)
                .datacenter(datacenter)
                .limitExceeded(limitExceeded)
                .priorEscalation(state.hasStickyAction())
                .build();
    }

    /**
     * Real browsers virtually always send these headers. Each missing one is
     * a weak signal; several missing together is a strong one.
     */
    private int countHeaderAnomalies(RequestMeta request) {
        int anomalies = 0;
        if (isBlank(request.userAgent())) {
            anomalies++;
        }
        if (isBlank(request.accept())) {
            anomalies++;
        }
        if (isBlank(request.acceptLanguage())) {
            anomalies++;
        }
        if (isBlank(request.acceptEncoding())) {
            anomalies++;
        }
        return anomalies;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
