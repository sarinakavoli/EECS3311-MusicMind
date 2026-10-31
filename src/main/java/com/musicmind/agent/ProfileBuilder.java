package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.TopItems;
import com.musicmind.domain.Track;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ListeningProfile} (F1) from what the tools returned. Genres are
 * aggregated from the user's top artists, weighted by rank so that the artists
 * played most influence the profile more, then normalised into a histogram.
 * (Spotify does not expose a genre on tracks, so artists are the source of genre
 * signal.) The aggregation is deterministic; the LLM never touches it.
 */
public class ProfileBuilder {

    private final int minItems;

    public ProfileBuilder(Config config) {
        this.minItems = config.profileMinItems();
    }

    public ListeningProfile build(TopItems topItems, List<Track> saved) {
        return build(topItems, saved, List.of());
    }

    /**
     * @param readNotices what could not be read from Spotify, if anything; any entry
     *                    makes the profile partial and is shown as a notice
     */
    public ListeningProfile build(TopItems topItems, List<Track> saved, List<String> readNotices) {
        List<Artist> artists = topItems.artists();
        List<Track> tracks = topItems.tracks();
        List<Track> savedTracks = saved == null ? List.of() : saved;

        List<String> notices = new ArrayList<>(readNotices);
        if (artists.size() < minItems || tracks.size() < minItems) {
            notices.add("Little listening history: " + artists.size() + " top artists and " + tracks.size()
                    + " top tracks found, and a full profile needs at least " + minItems + " of each.");
        }
        return new ListeningProfile(computeGenreHistogram(artists), artists, tracks,
                savedTracks.stream().map(Track::id).toList(), null, Instant.now(),
                !notices.isEmpty(), notices);
    }

    /**
     * Rank-weighted, normalised genre histogram, ordered from most to least
     * prominent. The i-th artist (0-based) contributes weight {@code (n-i)/n} to
     * each of its genres; weights are then scaled to sum to 1.0.
     */
    public Map<String, Double> computeGenreHistogram(List<Artist> artists) {
        Map<String, Double> weights = new LinkedHashMap<>();
        int n = artists.size();
        for (int i = 0; i < n; i++) {
            double rankWeight = (double) (n - i) / n;
            for (String genre : artists.get(i).genres()) {
                weights.merge(genre, rankWeight, Double::sum);
            }
        }
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> sorted = new LinkedHashMap<>();
        weights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), total > 0 ? e.getValue() / total : 0.0));
        return sorted;
    }
}
