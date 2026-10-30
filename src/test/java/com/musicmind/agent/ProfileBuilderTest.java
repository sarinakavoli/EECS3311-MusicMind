package com.musicmind.agent;

import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies genre aggregation (rank-weighted, normalised, ordered) in ProfileBuilder. */
class ProfileBuilderTest {

    private static MusicService musicWith(List<Artist> artists) {
        return new StubMusicService() {
            @Override public List<Artist> getTopArtists(String timeRange) { return artists; }
            @Override public List<Track> getTopItems(String type, String timeRange) { return List.of(); }
        };
    }

    @Test
    void aggregatesGenresRankWeightedAndNormalised() {
        List<Artist> artists = List.of(
                new Artist("a1", "A1", List.of("pop", "rock")),
                new Artist("a2", "A2", List.of("pop")),
                new Artist("a3", "A3", List.of("jazz")));

        // aggregateGenres returns an ordered map (most-to-least prominent).
        Map<String, Double> ordered = ProfileBuilder.aggregateGenres(artists);
        assertEquals(List.of("pop", "rock", "jazz"), new ArrayList<>(ordered.keySet()),
                "genres should be ordered most-to-least prominent");
        assertEquals(1.0, ordered.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9,
                "histogram should be normalised to 1.0");

        // The built profile exposes the same magnitudes and top genre.
        ListeningProfile profile = new ProfileBuilder(musicWith(artists)).build("medium_term");
        Map<String, Double> histogram = profile.genreHistogram();
        assertEquals("pop", profile.topGenre());
        assertTrue(histogram.get("pop") > histogram.get("rock"));
        assertTrue(histogram.get("rock") > histogram.get("jazz"));
    }

    @Test
    void emptyWhenNoListeningHistory() {
        ListeningProfile profile = new ProfileBuilder(musicWith(List.of())).build("short_term");
        assertTrue(profile.isEmpty());
        assertNull(profile.topGenre());
    }
}
