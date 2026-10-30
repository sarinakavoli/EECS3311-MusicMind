package com.musicmind.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A higher-level representation of a user's taste built from listening data. */
public class ListeningProfile {
    private final Map<String, Double> genreHistogram;
    private final List<Artist> topArtists;
    private final List<Track> topTracks;
    private final Instant generatedAt;

    public ListeningProfile(Map<String, Double> genreHistogram,
                            List<Artist> topArtists,
                            List<Track> topTracks,
                            Instant generatedAt) {
        this.genreHistogram = genreHistogram == null ? Map.of() : Map.copyOf(genreHistogram);
        this.topArtists = topArtists == null ? List.of() : List.copyOf(topArtists);
        this.topTracks = topTracks == null ? List.of() : List.copyOf(topTracks);
        this.generatedAt = generatedAt;
    }

    /** The single most prominent genre, or null when the profile is empty. */
    public String topGenre() {
        return genreHistogram.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public boolean isEmpty() {
        return topArtists.isEmpty() && topTracks.isEmpty();
    }

    public Map<String, Double> genreHistogram() { return genreHistogram; }
    public List<Artist> topArtists() { return topArtists; }
    public List<Track> topTracks() { return topTracks; }
    public Instant generatedAt() { return generatedAt; }
}
