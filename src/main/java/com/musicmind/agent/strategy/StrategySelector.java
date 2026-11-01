package com.musicmind.agent.strategy;

import com.musicmind.config.Config;

/** Chooses the {@link RecommendationStrategy} that fits a requested familiar share (1.0 = all familiar). */
public class StrategySelector {

    private final double familiarMin;
    private final double discoveryMax;
    private final Config config;

    public StrategySelector(Config config) {
        this.config = config;
        this.familiarMin = config.discoverFamiliarStrategyMin();
        this.discoveryMax = config.discoverDiscoveryStrategyMax();
    }

    public RecommendationStrategy select(double familiarRatio) {
        if (familiarRatio >= familiarMin) {
            return new FamiliarStrategy(config);
        }
        if (familiarRatio <= discoveryMax) {
            return new DiscoveryStrategy(config);
        }
        return new BalancedStrategy(config);
    }
}
