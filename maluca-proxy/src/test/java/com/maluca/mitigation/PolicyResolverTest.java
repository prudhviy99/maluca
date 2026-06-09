package com.maluca.mitigation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.maluca.TestFixtures;
import com.maluca.model.MitigationAction;

class PolicyResolverTest {

    private final PolicyResolver resolver = new PolicyResolver(TestFixtures.defaultProperties());

    @Test
    void bandsMapToProgressiveActions() {
        assertThat(resolver.resolve(0)).isEqualTo(MitigationAction.ALLOW);
        assertThat(resolver.resolve(29)).isEqualTo(MitigationAction.ALLOW);
        assertThat(resolver.resolve(30)).isEqualTo(MitigationAction.OBSERVE);
        assertThat(resolver.resolve(49)).isEqualTo(MitigationAction.OBSERVE);
        assertThat(resolver.resolve(50)).isEqualTo(MitigationAction.SOFT_LIMIT);
        assertThat(resolver.resolve(64)).isEqualTo(MitigationAction.SOFT_LIMIT);
        assertThat(resolver.resolve(65)).isEqualTo(MitigationAction.HARD_LIMIT);
        assertThat(resolver.resolve(74)).isEqualTo(MitigationAction.HARD_LIMIT);
        assertThat(resolver.resolve(75)).isEqualTo(MitigationAction.CHALLENGE);
        assertThat(resolver.resolve(89)).isEqualTo(MitigationAction.CHALLENGE);
        assertThat(resolver.resolve(90)).isEqualTo(MitigationAction.BLOCK);
        assertThat(resolver.resolve(100)).isEqualTo(MitigationAction.BLOCK);
    }

    @Test
    void severityIsMonotonicInScore() {
        MitigationAction previous = MitigationAction.ALLOW;
        for (int score = 0; score <= 100; score++) {
            MitigationAction action = resolver.resolve(score);
            assertThat(action.isAtLeastAsSevereAs(previous))
                    .as("score %d must not de-escalate below previous", score)
                    .isTrue();
            previous = action;
        }
    }
}
