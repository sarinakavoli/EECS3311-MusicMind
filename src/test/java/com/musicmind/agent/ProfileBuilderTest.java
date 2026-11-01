package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.TopItems;
import com.musicmind.domain.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBuilderTest {

    private final Config config = Config.of(Map.of());
    private final ProfileBuilder builder = new ProfileBuilder(config);

    static List<Artist> artists(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Artist("a" + i, "Artist " + i, List.of("pop"))).toList();
    }

    static List<Track> tracks(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Track("t" + i, "spotify:track:t" + i, "Title " + i, 1000, false, List.of("a" + i)))
                .toList();
    }

    @Test
    void genreHistogramIsRankWeightedNormalisedAndOrdered() {
        List<Artist> artists = List.of(
                new Artist("a1", "A1", List.of("pop", "rock")),
                new Artist("a2", "A2", List.of("pop")),
                new Artist("a3", "A3", List.of("jazz")));

        Map<String, Double> ordered = builder.computeGenreHistogram(artists);

        assertEquals(List.of("pop", "rock", "jazz"), new ArrayList<>(ordered.keySet()));
        assertEquals(1.0, ordered.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(ordered.get("pop") > ordered.get("rock"));
    }

    @Test
    void genreHistogramOfNoArtistsIsEmpty() {
        assertTrue(builder.computeGenreHistogram(List.of()).isEmpty());
    }

    @Test
    void buildAggregatesTopItemsAndSavedIdsIntoAFullProfile() {
        ListeningProfile p = builder.build(new TopItems(artists(6), tracks(6)), tracks(2));

        assertEquals("pop", p.topGenre());
        assertEquals(6, p.topArtists().size());
        assertEquals(List.of("t0", "t1"), p.savedTrackIds());
        assertFalse(p.isPartial());
        assertTrue(p.notices().isEmpty());
        assertNotNull(p.generatedAt());
    }

    @Test
    void fewerItemsThanTheConfiguredThresholdMakeAPartialProfileWithANotice() {
        int min = config.profileMinItems();
        ListeningProfile few = builder.build(new TopItems(artists(min - 1), tracks(min)), List.of());
        assertTrue(few.isPartial());
        assertEquals(1, few.notices().size());
        assertTrue(few.notices().get(0).contains(String.valueOf(min)), few.notices().get(0));

        assertFalse(builder.build(new TopItems(artists(min), tracks(min)), List.of()).isPartial());
    }

    @Test
    void anEmptyAccountIsPartialAndEmptyWithNoGenre() {
        ListeningProfile p = builder.build(new TopItems(List.of(), List.of()), List.of());
        assertTrue(p.isEmpty());
        assertTrue(p.isPartial());
        assertNull(p.topGenre());
    }

    @Test
    void failedReadsAreAddedAsNoticesAndForcePartial() {
        ListeningProfile p = builder.build(new TopItems(artists(8), tracks(8)), List.of(),
                List.of("Saved tracks could not be read: boom"));
        assertTrue(p.isPartial());
        assertTrue(p.notices().contains("Saved tracks could not be read: boom"));
    }

    @Test
    void someArtistsWithoutGenresAddACoverageNoticeWithCounts() {
        List<Artist> artists = new ArrayList<>(artists(4));
        artists.add(new Artist("x1", "X1", List.of()));
        artists.add(new Artist("x2", "X2", List.of()));

        ListeningProfile p = builder.build(new TopItems(artists, tracks(6)), tracks(1));

        assertEquals(1, p.notices().size(), p.notices().toString());
        assertTrue(p.notices().get(0).contains("4 of 6"), p.notices().get(0));
        assertTrue(p.notices().get(0).contains("MusicBrainz"), p.notices().get(0));
        assertTrue(p.isPartial());
    }

    @Test
    void noGenresAtAllAddsNoCoverageNoticeSoTheNoDataLineStandsAlone() {
        List<Artist> none = artists(6).stream().map(a -> new Artist(a.id(), a.name(), List.of())).toList();

        ListeningProfile p = builder.build(new TopItems(none, tracks(6)), tracks(1));

        assertTrue(p.notices().isEmpty(), p.notices().toString());
    }
}
