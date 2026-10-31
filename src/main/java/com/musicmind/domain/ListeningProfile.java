package com.musicmind.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A higher-level representation of a user's taste built from listening data. */
public class ListeningProfile {
    private final Map<String, Double> genreHistogram;
    private final List<Artist> topArtists;
    private final List<Track> topTracks;
    private final List<String> savedTrackIds;
    private final Instant generatedAt;
    private final boolean partial;
    private final List<String> notices;
    private String summary;
    private String range;

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
        this(genreHistogram, topArtists, topTracks, savedTrackIds, summary, generatedAt,
                onlyOneSourceHasData(topArtists, topTracks), List.of());
    }

    /**
     * @param partial true when the profile rests on little history or a failed read
     * @param notices what is missing, in words for the user
     */
    public ListeningProfile(Map<String, Double> genreHistogram,
                            List<Artist> topArtists,
                            List<Track> topTracks,
                            List<String> savedTrackIds,
                            String summary,
                            Instant generatedAt,
                            boolean partial,
                            List<String> notices) {
        this.partial = partial;
        this.notices = notices == null ? List.of() : List.copyOf(notices);
        this.genreHistogram = genreHistogram == null ? Map.of() : Map.copyOf(genreHistogram);
        this.topArtists = topArtists == null ? List.of() : List.copyOf(topArtists);
        this.topTracks = topTracks == null ? List.of() : List.copyOf(topTracks);
        this.savedTrackIds = savedTrackIds == null ? List.of() : List.copyOf(savedTrackIds);
        this.summary = summary;
        this.generatedAt = generatedAt;
    }

    private static boolean onlyOneSourceHasData(List<Artist> artists, List<Track> tracks) {
        boolean noArtists = artists == null || artists.isEmpty();
        boolean noTracks = tracks == null || tracks.isEmpty();
        return noArtists != noTracks;
    }

    /**
     * True when the profile rests on little history or a Spotify read that failed.
     * {@code ProfileBuilder} decides this against the configured threshold; {@link #notices}
     * says what is missing.
     */
    public boolean isPartial() {
        return partial;
    }

    /** Genres from most to least prominent, at most {@code limit}. */
    public List<Map.Entry<String, Double>> rankedGenres(int limit) {
        return genreHistogram.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .toList();
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
    public List<String> notices() { return notices; }
    /** The time range this profile covers (short, medium or long), or null if unknown. */
    public String range() { return range; }
    public void setRange(String range) { this.range = range; }
}
