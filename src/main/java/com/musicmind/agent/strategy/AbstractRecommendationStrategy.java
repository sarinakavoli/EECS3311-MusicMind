package com.musicmind.agent.strategy;

import com.musicmind.config.Config;
import com.musicmind.domain.ListeningProfile;
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

    private ListeningProfile profile;

    /** R7: the engine binds the profile before it asks for candidates. */
    @Override
    public void bindProfile(ListeningProfile profile) {
        this.profile = profile;
    }

    /** The bound profile; concrete strategies call the helpers with it. */
    protected ListeningProfile profile() {
        if (profile == null) {
            throw new IllegalStateException("bindProfile(profile) must be called before selectCandidates.");
        }
        return profile;
    }

    /** Protected helper: 1.0 if the track is by an artist the profile knows, else 0.0. */
    protected double scoreFamiliarity(Track track, ListeningProfile profile) {
        return profile.familiarity(track);
    }

    /** Protected helper: drop tracks whose id is already in the user's library (top and saved tracks). */
    protected List<Track> excludeKnown(List<Track> candidates, ListeningProfile profile) {
        Set<String> known = profile.knownTrackIds();
        List<Track> out = new ArrayList<>();
        for (Track t : candidates) {
            if (!known.contains(t.id())) out.add(t);
        }
        return out;
    }

    /** Number of tracks to aim for given the spec: its duration over the average track length, else the default. */
    public static int targetCount(PlaylistSpec spec) {
        Config config = Config.defaults();
        if (spec != null && spec.targetMinutes() > 0) {
            return Math.max(1, spec.targetMinutes() / config.avgTrackMinutes());
        }
        return config.defaultTrackCount();
    }

    protected List<Track> take(List<Track> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    @Override
    public abstract List<Track> selectCandidates(Map<String, List<Track>> pools, PlaylistSpec spec);
}
