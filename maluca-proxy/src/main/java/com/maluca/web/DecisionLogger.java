package com.maluca.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.maluca.model.ClientIdentity;
import com.maluca.model.ClientState;
import com.maluca.model.MitigationAction;

/**
 * One structured line per decision. With the json-logging profile these come
 * out as JSON objects; in dev they print as key=value pairs. Either way the
 * signals that produced a decision are always reconstructible from the log.
 */
@Component
public class DecisionLogger {

    private static final Logger log = LoggerFactory.getLogger("maluca.decision");

    public void log(ClientIdentity identity, MitigationAction action, String path,
                    String reason, ClientState state) {
        // {} placeholders make kv() pairs visible in plain-text logs;
        // the JSON encoder emits them as first-class fields either way.
        log.info("decision {} {} {} {} {} {} {} {} {} {}",
                kv("client", identity.compositeKey()),
                kv("action", action.name()),
                kv("path", path),
                kv("reason", reason),
                kv("c10s", state.countLast10s()),
                kv("c60s", state.countLast60s()),
                kv("c5m", state.countLast5m()),
                kv("paths30s", state.distinctPaths30s()),
                kv("sens60s", state.sensitiveHits60s()),
                kv("fourxx60s", state.fourxxLast60s()));
    }
}
