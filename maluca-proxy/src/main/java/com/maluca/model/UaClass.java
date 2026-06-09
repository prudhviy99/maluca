package com.maluca.model;

/** Coarse user-agent classification. One signal among many — never decisive alone. */
public enum UaClass {
    BROWSER,
    MOBILE_APP,
    VERIFIED_BOT,
    KNOWN_BAD_BOT,
    SCRIPT_CLIENT,
    UNKNOWN
}
