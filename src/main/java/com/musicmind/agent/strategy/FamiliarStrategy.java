package com.musicmind.agent.strategy;

import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Selection policy that stays close to the user's familiar taste. */
public class FamiliarStrategy extends AbstractRecommendationStrategy {

    @Override
    public List<Track> selectCandidates(Map<String, List<Track>> pools, PlaylistSpec spec) {
        int count = targetCount(spec);
        List<Track> result = new ArrayList<>(take(pools.getOrDefault("familiar", List.of()), count));
        if (result.size() < count) {
            result.addAll(take(pools.getOrDefault("novel", List.of()), count - result.size()));
        }
        return result;
    }
}
