package com.maluca.scoring;

import org.springframework.stereotype.Component;

import com.maluca.identity.UaClassifier;
import com.maluca.model.ClientState;
import com.maluca.model.RequestMeta;
import com.maluca.model.RiskSignals;

/**
 * Pure function: (request, state) → signals. No I/O happens here — Redis
 * state is collected beforehand — which is what makes the whole scoring
 * pipeline unit-testable without infrastructure.
 */
@Component
public class SignalsCollector {

    private final UaClassifier uaClassifier;

    public SignalsCollector(UaClassifier uaClassifier) {
        this.uaClassifier = uaClassifier;
    }

    public RiskSignals collect(RequestMeta request, ClientState state, boolean limitExceeded) {
        return RiskSignals.builder()
                .burst10s(state.countLast10s())
                .sustained60s(state.countLast60s())
                .distinctPaths30s(state.distinctPaths30s())
                .sensitiveHits60s(state.sensitiveHits60s())
                .fourxx60s(state.fourxxLast60s())
                .headerAnomalies(countHeaderAnomalies(request))
                .uaClass(uaClassifier.classify(request.userAgent()))
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
