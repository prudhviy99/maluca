package com.maluca.model;

import java.util.Map;

/**
 * A risk score plus the per-signal contributions that produced it. The
 * contributions map is what makes every decision explainable: it is logged
 * verbatim, so "why was this client challenged?" is always answerable.
 */
public record ScoreResult(int score, Map<String, Double> contributions) {

    public static final ScoreResult ZERO = new ScoreResult(0, Map.of());
}
