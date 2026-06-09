package com.maluca.model;

/**
 * Everything interesting about a request, in one flat bag. Collected without
 * I/O — state comes from Redis beforehand, request attributes from
 * {@link RequestMeta}. The scorer turns this into a 0–100 score.
 */
public record RiskSignals(
        long burst10s,
        long sustained60s,
        long distinctPaths30s,
        long sensitiveHits60s,
        long fourxx60s,
        int headerAnomalies,
        UaClass uaClass,
        boolean limitExceeded,
        boolean priorEscalation,
        boolean onDenylist) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long burst10s;
        private long sustained60s;
        private long distinctPaths30s;
        private long sensitiveHits60s;
        private long fourxx60s;
        private int headerAnomalies;
        private UaClass uaClass = UaClass.UNKNOWN;
        private boolean limitExceeded;
        private boolean priorEscalation;
        private boolean onDenylist;

        public Builder burst10s(long v) { this.burst10s = v; return this; }
        public Builder sustained60s(long v) { this.sustained60s = v; return this; }
        public Builder distinctPaths30s(long v) { this.distinctPaths30s = v; return this; }
        public Builder sensitiveHits60s(long v) { this.sensitiveHits60s = v; return this; }
        public Builder fourxx60s(long v) { this.fourxx60s = v; return this; }
        public Builder headerAnomalies(int v) { this.headerAnomalies = v; return this; }
        public Builder uaClass(UaClass v) { this.uaClass = v; return this; }
        public Builder limitExceeded(boolean v) { this.limitExceeded = v; return this; }
        public Builder priorEscalation(boolean v) { this.priorEscalation = v; return this; }
        public Builder onDenylist(boolean v) { this.onDenylist = v; return this; }

        public RiskSignals build() {
            return new RiskSignals(burst10s, sustained60s, distinctPaths30s, sensitiveHits60s,
                    fourxx60s, headerAnomalies, uaClass, limitExceeded, priorEscalation, onDenylist);
        }
    }
}
