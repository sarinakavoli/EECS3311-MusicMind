package com.musicmind.agent.strategy;

import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstract base of the Strategy hierarchy. It implements the
 * {@link RecommendationStrategy} interface and factors out the scoring and
 * exclusion logic common to all policies; each concrete strategy overrides
 * only {@link #selectCandidates}.
 */
public abstract class AbstractRecommendationStrategy implements RecommendationStrategy {

    /** Rough average track length used to turn a target duration into a count. */
    private static final int AVG_TRACK_MINUTES = 4;

    /** Protected helper: 1.0 if the track is by a known artist, else 0.0. */
    protected double scoreFamiliarity(Track track, Set<String> knownArtistIds) {
        for (String a : track.artistIds()) {
            if (knownArtistIds.contains(a)) return 1.0;
        }
        return 0.0;
    }

    /** Protected helper: drop tracks whose id is already in the user's library. */
    protected List<Track> excludeKnown(List<Track> candidates, Set<String> knownTrackIds) {
        List<Track> out = new ArrayList<>();
        for (Track t : candidates) {
            if (!knownTrackIds.contains(t.id())) out.add(t);
        }
        return out;
    }

    /** Number of tracks to aim for given the spec. */
    protected int targetCount(PlaylistSpec spec) {
        if (spec != null && spec.targetMinutes() > 0) {
            return Math.max(1, spec.targetMinutes() / AVG_TRACK_MINUTES);
        }
        return 20;
    }

    protected List<Track> take(List<Track> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    @Override
    public abstract List<Track> selectCandidates(Map<String, List<Track>> pools, PlaylistSpec spec);
}
