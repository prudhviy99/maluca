package com.maluca.metrics;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;

/**
 * Wraps a Mono in a Micrometer Observation, which surfaces as a child span
 * (OTel) and a timer. Used for the three sub-steps an operator cares about
 * when p99 moves: state collection (redis), the limiter (redis), upstream.
 */
public final class Observed {

    private Observed() {
    }

    public static <T> Mono<T> mono(ObservationRegistry registry, String name, Mono<T> source) {
        return Mono.defer(() -> {
            Observation observation = Observation.start(name, registry);
            return source
                    .doOnError(observation::error)
                    .doFinally(signal -> observation.stop());
        });
    }
}
