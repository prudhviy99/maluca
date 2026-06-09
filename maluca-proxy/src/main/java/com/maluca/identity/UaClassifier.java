package com.maluca.identity;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.maluca.model.UaClass;

/**
 * Coarse UA classification from a static rule set. Phase 5 layers verified-bot
 * FCrDNS checks on top; this class only ever looks at the string itself.
 */
@Component
public class UaClassifier {

    public UaClass classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UaClass.UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);

        if (ua.contains("googlebot") || ua.contains("bingbot") || ua.contains("duckduckbot")
                || ua.contains("applebot") || ua.contains("yandexbot")) {
            // claimed identity only — FCrDNS verification decides whether to believe it
            return UaClass.VERIFIED_BOT;
        }
        if (ua.contains("zgrab") || ua.contains("masscan") || ua.contains("nikto")
                || ua.contains("sqlmap") || ua.contains("nmap")) {
            return UaClass.KNOWN_BAD_BOT;
        }
        if (ua.startsWith("curl/") || ua.startsWith("wget/") || ua.contains("python-requests")
                || ua.contains("python-urllib") || ua.contains("go-http-client")
                || ua.contains("scrapy") || ua.contains("httpclient") || ua.contains("okhttp")
                || ua.contains("aiohttp") || ua.contains("java/")) {
            return UaClass.SCRIPT_CLIENT;
        }
        if (ua.contains("mozilla/") && (ua.contains("chrome") || ua.contains("safari")
                || ua.contains("firefox") || ua.contains("edg/"))) {
            return ua.contains("mobile") ? UaClass.MOBILE_APP : UaClass.BROWSER;
        }
        return UaClass.UNKNOWN;
    }
}
