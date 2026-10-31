package com.musicmind.agent.strategy;

import com.musicmind.config.Config;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default policy: blend familiar and novel tracks according to the spec's
 * familiar-vs-explore ratio (e.g. 70% familiar / 30% new). Novel tracks the
 * user already has are dropped; a short pool is topped up from the other one.
 */
public class BalancedStrategy extends AbstractRecommendationStrategy {

    public BalancedStrategy() {
    }

    public BalancedStrategy(Config config) {
        super(config);
    }

    @Override
    public List<Track> selectCandidates(Map<String, List<Track>> pools, PlaylistSpec spec) {
        int count = targetCount(spec);
        double familiarRatio = spec == null ? config().discoverDefaultFamiliarRatio() : spec.familiarRatio();
        int familiarCount = (int) Math.round(count * familiarRatio);
        int novelCount = count - familiarCount;

        List<Track> familiar = pools.getOrDefault("familiar", List.of());
        List<Track> novel = excludeKnown(pools.getOrDefault("novel", List.of()), profile());

        List<Track> result = new ArrayList<>();
        result.addAll(take(familiar, familiarCount));
        result.addAll(take(novel, novelCount));
        // Top up from whichever pool still has tracks when the other fell short.
        int missing = count - result.size();
        if (missing > 0) {
            result.addAll(take(novel.subList(Math.min(novelCount, novel.size()), novel.size()), missing));
            missing = count - result.size();
        }
        if (missing > 0) {
            result.addAll(take(familiar.subList(Math.min(familiarCount, familiar.size()), familiar.size()), missing));
        }
        return result;
    }
}
