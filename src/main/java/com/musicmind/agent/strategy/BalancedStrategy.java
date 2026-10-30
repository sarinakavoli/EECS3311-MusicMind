package com.musicmind.agent.strategy;

import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default policy: blend familiar and novel tracks according to the spec's
 * familiar-vs-explore ratio (e.g. 70% familiar / 30% new).
 */
public class BalancedStrategy extends AbstractRecommendationStrategy {

    @Override
    public List<Track> selectCandidates(Map<String, List<Track>> pools, PlaylistSpec spec) {
        int count = targetCount(spec);
        double familiarRatio = spec == null ? 0.7 : spec.familiarRatio();
        int familiarCount = (int) Math.round(count * familiarRatio);
        int novelCount = count - familiarCount;

        List<Track> result = new ArrayList<>();
        result.addAll(take(pools.getOrDefault("familiar", List.of()), familiarCount));
        result.addAll(take(pools.getOrDefault("novel", List.of()), novelCount));
        return result;
    }
}
