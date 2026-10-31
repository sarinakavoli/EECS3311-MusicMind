package com.musicmind.domain;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A higher-level representation of a user's taste built from listening data. */
public class ListeningProfile {
    private final Map<String, Double> genreHistogram;
    private final List<Artist> topArtists;
    private final List<Track> topTracks;
    private final List<String> savedTrackIds;
    private final Instant generatedAt;
    private String summary;

    public ListeningProfile(Map<String, Double> genreHistogram,
                            List<Artist> topArtists,
                            List<Track> topTracks,
                            Instant generatedAt) {
        this(genreHistogram, topArtists, topTracks, null, null, generatedAt);
    }

    public ListeningProfile(Map<String, Double> genreHistogram,
                            List<Artist> topArtists,
                            List<Track> topTracks,
                            List<String> savedTrackIds,
                            String summary,
                            Instant generatedAt) {
        this.genreHistogram = genreHistogram == null ? Map.of() : Map.copyOf(genreHistogram);
        this.topArtists = topArtists == null ? List.of() : List.copyOf(topArtists);
        this.topTracks = topTracks == null ? List.of() : List.copyOf(topTracks);
        this.savedTrackIds = savedTrackIds == null ? List.of() : List.copyOf(savedTrackIds);
        this.summary = summary;
        this.generatedAt = generatedAt;
    }

    /**
     * True when only one of top artists and top tracks has data, i.e. a Spotify
     * read came back empty and the profile rests on the other source alone.
     */
    public boolean isPartial() {
        return topArtists.isEmpty() != topTracks.isEmpty();
    }

    /** Artists the user already knows: top artists plus the artists of top tracks. */
    public Set<String> knownArtistIds() {
        Set<String> ids = new HashSet<>();
        topArtists.forEach(a -> ids.add(a.id()));
        topTracks.forEach(t -> ids.addAll(t.artistIds()));
        return ids;
    }

    /** Tracks the user already knows: top tracks plus saved tracks. */
    public Set<String> knownTrackIds() {
        Set<String> ids = new HashSet<>(savedTrackIds);
        topTracks.forEach(t -> ids.add(t.id()));
        return ids;
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
    public List<String> savedTrackIds() { return savedTrackIds; }
    public String summary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant generatedAt() { return generatedAt; }
}
