package com.maluca.model;

/**
 * Snapshot of a client's recent behavior, read+updated in a single atomic
 * Redis round trip per request (see lua/collect_state.lua).
 */
public record ClientState(
        long countLast10s,
        long countLast60s,
        long countLast5m,
        long countLast1h,
        long distinctPaths30s,
        long sensitiveHits60s,
        long fourxxLast60s,
        String stickyAction,
        long stickyTtlSeconds) {

    public static final ClientState EMPTY =
            new ClientState(0, 0, 0, 0, 0, 0, 0, "", -2);

    public boolean hasStickyAction() {
        return stickyAction != null && !stickyAction.isBlank() && stickyTtlSeconds > 0;
    }
}
