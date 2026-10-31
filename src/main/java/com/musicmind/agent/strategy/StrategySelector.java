package com.musicmind.agent.strategy;

import com.musicmind.config.Config;

/** Chooses the {@link RecommendationStrategy} that fits a requested familiar share (1.0 = all familiar). */
public class StrategySelector {

    private final double familiarMin;
    private final double discoveryMax;

    public StrategySelector(Config config) {
        this.familiarMin = config.discoverFamiliarStrategyMin();
        this.discoveryMax = config.discoverDiscoveryStrategyMax();
    }

    public RecommendationStrategy select(double familiarRatio) {
        if (familiarRatio >= familiarMin) {
            return new FamiliarStrategy();
        }
        if (familiarRatio <= discoveryMax) {
            return new DiscoveryStrategy();
        }
        return new BalancedStrategy();
    }
}
