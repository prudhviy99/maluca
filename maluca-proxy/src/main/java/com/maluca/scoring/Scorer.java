package com.maluca.scoring;

import com.maluca.model.RiskSignals;
import com.maluca.model.ScoreResult;

/** Turns signals into a 0–100 risk score. Pure — no I/O, no clocks. */
public interface Scorer {

    ScoreResult score(RiskSignals signals);
}
