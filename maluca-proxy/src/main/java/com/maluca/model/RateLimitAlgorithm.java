package com.maluca.model;

public enum RateLimitAlgorithm {
    FIXED_WINDOW,
    SLIDING_WINDOW_COUNTER,
    SLIDING_WINDOW_LOG,
    TOKEN_BUCKET,
    LEAKY_BUCKET
}
