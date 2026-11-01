package com.musicmind.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainAdditionsTest {

    private static final Track T1 = new Track("t1", "u1", "x", 1, false, List.of("a1", "a2"));
    private static final Artist A3 = new Artist("a3", "n", List.of("pop"));

    @Test
    void searchQueryBuildsASpotifyQueryFromKeywordsGenreAndFilters() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("year", "2019-2021");
        filters.put("artist", "Daft Punk");
        SearchQuery q = new SearchQuery("late night", "synth pop", filters, 0.8);
        assertEquals("late night synth pop year:2019-2021 artist:\"Daft Punk\"", q.toSpotifyQuery());
        assertEquals(0.8, q.confidence());
    }

    @Test
    void searchQueryNeverEmitsAGenreFilter() {
        assertEquals("upbeat indie running", new SearchQuery("upbeat indie running", "indie", Map.of(), 1).toSpotifyQuery());
        assertEquals("upbeat indie running", new SearchQuery("upbeat indie running", "Indie", Map.of(), 1).toSpotifyQuery());
        assertEquals("upbeat running indie", new SearchQuery("upbeat running", "indie", Map.of(), 1).toSpotifyQuery());
        assertEquals("popular songs pop", new SearchQuery("popular songs", "pop", Map.of(), 1).toSpotifyQuery());
        assertEquals("unpopular pop", new SearchQuery("unpopular", "pop", Map.of(), 1).toSpotifyQuery());
        assertEquals("pop hits", new SearchQuery("pop hits", "pop", Map.of(), 1).toSpotifyQuery());
        assertEquals("indie rock", new SearchQuery(null, "indie rock", Map.of(), 1).toSpotifyQuery());
        assertEquals("rock year:1999", new SearchQuery("rock", null, Map.of("year", "1999"), 1).toSpotifyQuery());
    }

    @Test
    void searchQueryWithOnlyKeywordsIsJustTheKeywords() {
        assertEquals("rain", new SearchQuery("rain", null, null, 1.0).toSpotifyQuery());
    }

    @Test
    void searchQueryWithOnlyAGenreOmitsKeywords() {
        assertEquals("jazz", new SearchQuery(" ", "jazz", Map.of(), 0.5).toSpotifyQuery());
    }

    @Test
    void listeningProfileKnowsItsFamiliarityBaseline() {
        ListeningProfile p = new ListeningProfile(Map.of("pop", 1.0), List.of(A3), List.of(T1),
                List.of("s1"), "summary", Instant.EPOCH);
        assertEquals(Set.of("a1", "a2", "a3"), p.knownArtistIds());
        assertEquals(Set.of("t1", "s1"), p.knownTrackIds());
        assertEquals(List.of("s1"), p.savedTrackIds());
        assertEquals("summary", p.summary());
    }

    @Test
    void oldConstructorHasNoSavedTracksOrSummary() {
        ListeningProfile p = new ListeningProfile(Map.of(), List.of(A3), List.of(), Instant.EPOCH);
        assertTrue(p.savedTrackIds().isEmpty());
        assertNull(p.summary());
        p.setSummary("later");
        assertEquals("later", p.summary());
    }

    @Test
    void profileIsPartialWhenOnlyOneSourceHasData() {
        Instant now = Instant.EPOCH;
        assertTrue(new ListeningProfile(Map.of(), List.of(A3), List.of(), now).isPartial());
        assertTrue(new ListeningProfile(Map.of(), List.of(), List.of(T1), now).isPartial());
        assertFalse(new ListeningProfile(Map.of(), List.of(A3), List.of(T1), now).isPartial());
        assertFalse(new ListeningProfile(Map.of(), List.of(), List.of(), now).isPartial());
    }

    @Test
    void playlistSpecBlockedArtistsCanBeReplaced() {
        PlaylistSpec spec = new PlaylistSpec(10, true, 0.5, List.of("a"), "n");
        spec.setBlockedArtists(List.of("b", "c"));
        assertTrue(spec.isBlocked("c"));
        assertFalse(spec.isBlocked("a"));
        spec.setBlockedArtists(null);
        assertTrue(spec.blockedArtists().isEmpty());
    }

    @Test
    void agentRequestCarriesOptionalConstraints() {
        AgentRequest plain = new AgentRequest("mix", RequestKind.CREATE_PLAYLIST);
        assertNull(plain.constraints());
        PlaylistSpec spec = new PlaylistSpec(30, true, 0.5, List.of(), "n");
        assertSame(spec, new AgentRequest("mix", RequestKind.CREATE_PLAYLIST, spec).constraints());
    }

    @Test
    void feasibilityReportIsImmutableAndFeasibleWithoutConflicts() {
        FeasibilityReport ok = new FeasibilityReport(true, null, null);
        assertTrue(ok.conflicts().isEmpty());
        FeasibilityReport bad = new FeasibilityReport(false, List.of("too short"), List.of("allow explicit"));
        assertFalse(bad.feasible());
        assertEquals(List.of("allow explicit"), bad.relaxationOptions());
    }

    @Test
    void trendReportDiffsAreCopiedAndNarrativeCanBeSet() {
        TrendReport r = new TrendReport(
                Map.of("short_term", List.of(A3)), Map.of("short_term", Map.of("pop", 1.0)),
                List.of("pop"), List.of("rock"), List.of("a3"), List.of(), List.of("no long-term data"), null);
        assertEquals(List.of("pop"), r.addedGenres());
        assertEquals(List.of("no long-term data"), r.gaps());
        assertEquals("text", r.withNarrative("text").narrative());
        assertNull(r.narrative());
    }

    @Test
    void topItemsHoldsBothLists() {
        TopItems t = new TopItems(List.of(A3), null);
        assertEquals(List.of(A3), t.artists());
        assertTrue(t.tracks().isEmpty());
    }

    @Test
    void aTraceStoredBeforeRequestedGenresExistedStillLoadsWithNone() {
        String old = "{\"strategyName\":\"BalancedStrategy\",\"reasons\":{\"t1\":\"x\"},\"constraintsSatisfied\":[\"y\"]}";
        DecisionTrace trace = new com.google.gson.Gson().fromJson(old, DecisionTrace.class);
        assertEquals(List.of(), trace.requestedGenres());
        assertEquals(List.of("y"), trace.constraintsSatisfied());
        String roundTrip = new com.google.gson.Gson().toJson(new DecisionTrace("S", Map.of("t1", "x"), List.of(), List.of("dance")));
        assertEquals(List.of("dance"), new com.google.gson.Gson().fromJson(roundTrip, DecisionTrace.class).requestedGenres());
    }
}
