package com.maluca.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.maluca.model.ClientIdentity;
import com.maluca.model.Decision;

/**
 * One structured line per decision, including the per-signal score
 * contributions — every mitigation is traceable to the signals that caused
 * it. JSON profile emits these as first-class fields.
 */
@Component
public class DecisionLogger {

    private static final Logger log = LoggerFactory.getLogger("maluca.decision");

    public void log(ClientIdentity identity, Decision decision, String path) {
        log.info("decision {} {} {} {} {} {} {}",
                kv("client", identity.compositeKey()),
                kv("action", decision.action().name()),
                kv("score", decision.score()),
                kv("path", path),
                kv("reason", decision.reason()),
                kv("dryRun", decision.dryRun()),
                kv("signals", decision.contributions()));
    }
}
