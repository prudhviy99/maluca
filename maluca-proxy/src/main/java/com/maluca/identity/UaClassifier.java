package com.maluca.identity;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.maluca.model.UaClass;

/**
 * Coarse UA classification from a substring rule table loaded from
 * {@code ua-classes.yml} (first match wins, in file order). A static file is
 * the right v1; the production evolution is the same table refreshed from a
 * feed — the lookup code doesn't change.
 *
 * <p>VERIFIED_BOT here means <em>claimed</em> — {@link VerifiedBotService}
 * decides whether to believe it via FCrDNS.
 */
@Component
public class UaClassifier {

    private static final Logger log = LoggerFactory.getLogger(UaClassifier.class);

    private final Map<String, UaClass> rules = new LinkedHashMap<>();

    public UaClassifier() {
        try (InputStream in = new ClassPathResource("ua-classes.yml").getInputStream()) {
            Map<String, List<String>> loaded = new YAMLMapper().readValue(in, new TypeReference<>() {
            });
            loaded.forEach((className, patterns) -> {
                UaClass uaClass = UaClass.valueOf(className.toUpperCase(Locale.ROOT));
                patterns.forEach(p -> rules.put(p.toLowerCase(Locale.ROOT), uaClass));
            });
            log.info("ua_classifier_loaded rules={}", rules.size());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load ua-classes.yml", e);
        }
    }

    public UaClass classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UaClass.UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, UaClass> rule : rules.entrySet()) {
            if (ua.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        if (ua.contains("mozilla/") && (ua.contains("chrome") || ua.contains("safari")
                || ua.contains("firefox") || ua.contains("edg/"))) {
            return ua.contains("mobile") ? UaClass.MOBILE_APP : UaClass.BROWSER;
        }
        return UaClass.UNKNOWN;
    }
}
