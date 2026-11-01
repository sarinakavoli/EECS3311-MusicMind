package com.musicmind.agent.strategy;

import com.musicmind.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StrategySelectorTest {

    private final StrategySelector selector = new StrategySelector(Config.of(Map.of()));

    @Test
    void highFamiliarShareSelectsFamiliar() {
        assertInstanceOf(FamiliarStrategy.class, selector.select(1.0));
        assertInstanceOf(FamiliarStrategy.class, selector.select(0.85));
    }

    @Test
    void lowFamiliarShareSelectsDiscovery() {
        assertInstanceOf(DiscoveryStrategy.class, selector.select(0.0));
        assertInstanceOf(DiscoveryStrategy.class, selector.select(0.15));
    }

    @Test
    void everythingElseIsBalanced() {
        assertInstanceOf(BalancedStrategy.class, selector.select(0.7));
        assertInstanceOf(BalancedStrategy.class, selector.select(0.5));
    }
}
