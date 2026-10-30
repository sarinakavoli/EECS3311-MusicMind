package com.musicmind.agent;

import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ListeningProfile} (F1) from the user's Spotify data. Genres
 * are aggregated from the user's top artists, weighted by rank so that the
 * artists you listen to most influence the profile more, then normalised into a
 * histogram. (Spotify does not expose a genre on tracks, so artists are the
 * source of genre signal.)
 */
public class ProfileBuilder {

    private final MusicService music;

    public ProfileBuilder(MusicService music) {
        this.music = music;
    }

    public ListeningProfile build(String timeRange) {
        List<Artist> topArtists = music.getTopArtists(timeRange);
        List<Track> topTracks = music.getTopItems("tracks", timeRange);
        Map<String, Double> histogram = aggregateGenres(topArtists);
        return new ListeningProfile(histogram, topArtists, topTracks, Instant.now());
    }

    /**
     * Rank-weighted, normalised genre histogram, ordered from most to least
     * prominent. The i-th artist (0-based) contributes weight {@code (n-i)/n} to
     * each of its genres; weights are then scaled to sum to 1.0.
     */
    public static Map<String, Double> aggregateGenres(List<Artist> artists) {
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
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(e.getKey(), total > 0 ? e.getValue() / total : 0.0));
        return sorted;
    }
}
