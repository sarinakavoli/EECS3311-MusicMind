package com.musicmind.agent.strategy;

import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Selection policy that maximises novelty: tracks the user does not have,
 * from artists the user does not know first, then new tracks by known artists,
 * and only then familiar tracks to fill the count.
 */
public class DiscoveryStrategy extends AbstractRecommendationStrategy {

    @Override
    public List<Track> selectCandidates(Map<String, List<Track>> pools, PlaylistSpec spec) {
        int count = targetCount(spec);
        ListeningProfile known = profile();
        List<Track> novel = excludeKnown(pools.getOrDefault("novel", List.of()), known);

        List<Track> unknownArtists = new ArrayList<>();
        List<Track> knownArtists = new ArrayList<>();
        for (Track t : novel) {
            (scoreFamiliarity(t, known) == 0.0 ? unknownArtists : knownArtists).add(t);
        }
        List<Track> result = new ArrayList<>(take(unknownArtists, count));
        if (result.size() < count) {
            result.addAll(take(knownArtists, count - result.size()));
        }
        if (result.size() < count) {
            result.addAll(take(pools.getOrDefault("familiar", List.of()), count - result.size()));
        }
        return result;
    }
}
