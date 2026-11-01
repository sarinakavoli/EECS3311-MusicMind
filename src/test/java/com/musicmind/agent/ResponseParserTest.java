package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.Track;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.ParseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserTest {

    private final Config config = Config.of(Map.of());
    private final ResponseParser parser = new ResponseParser(config);

    private static Track track(String id) {
        return new Track(id, "spotify:track:" + id, "", 200000, false, List.of());
    }

    // ---- parsePlaylistSpec ----

    @Test
    void aClampRangeWhoseMinimumExceedsItsMaximumIsRejectedUpFront() {
        Config broken = config.withTunables(Map.of("parser.minTargetMinutes", "700"));
        assertThrows(IllegalStateException.class, () -> new ResponseParser(broken));
    }

    @Test
    void parsesAValidPlaylistSpec() {
        PlaylistSpec s = parser.parsePlaylistSpec("{\"targetMinutes\":90,\"allowExplicit\":true,"
                + "\"familiarRatio\":0.25,\"blockedArtists\":[\"a1\",\"a2\"],\"name\":\"Focus\"}");
        assertEquals(90, s.targetMinutes());
        assertTrue(s.allowExplicit());
        assertEquals(0.25, s.familiarRatio(), 1e-9);
        assertEquals(List.of("a1", "a2"), s.blockedArtists());
        assertEquals("Focus", s.name());
    }

    @Test
    void toleratesFencedJsonAndSurroundingProse() {
        String fenced = "```json\n{\"targetMinutes\":30,\"familiarRatio\":0.5}\n```";
        assertEquals(30, parser.parsePlaylistSpec(fenced).targetMinutes());
        String prose = "Here you go: {\"targetMinutes\":45} hope that helps";
        assertEquals(45, parser.parsePlaylistSpec(prose).targetMinutes());
    }

    @Test
    void clampsOutOfRangeValues() {
        PlaylistSpec s = parser.parsePlaylistSpec("{\"targetMinutes\":-5,\"familiarRatio\":1.7}");
        assertEquals(config.parserMinTargetMinutes(), s.targetMinutes());
        assertEquals(1.0, s.familiarRatio(), 1e-9);
        PlaylistSpec t = parser.parsePlaylistSpec("{\"targetMinutes\":99999,\"familiarRatio\":-0.3}");
        assertEquals(config.parserMaxTargetMinutes(), t.targetMinutes());
        assertEquals(0.0, t.familiarRatio(), 1e-9);
    }

    @Test
    void missingFieldsTakeConfiguredDefaults() {
        PlaylistSpec s = parser.parsePlaylistSpec("{}");
        assertEquals(config.parserDefaultTargetMinutes(), s.targetMinutes());
        assertEquals(config.parserDefaultFamiliarRatio(), s.familiarRatio(), 1e-9);
        assertEquals(config.parserDefaultPlaylistName(), s.name());
        assertTrue(s.blockedArtists().isEmpty());
    }

    @Test
    void malformedOrWrongTypedJsonRaisesParseException() {
        assertThrows(ParseException.class, () -> parser.parsePlaylistSpec("not json at all"));
        assertThrows(ParseException.class, () -> parser.parsePlaylistSpec("{\"targetMinutes\":"));
        assertThrows(ParseException.class, () -> parser.parsePlaylistSpec(null));
        assertThrows(ParseException.class, () -> parser.parsePlaylistSpec("[1,2]"));
        assertThrows(ParseException.class, () -> parser.parsePlaylistSpec("{\"targetMinutes\":\"soon\"}"));
        assertThrows(ParseException.class, () -> parser.parsePlaylistSpec("{\"blockedArtists\":\"a1\"}"));
    }

    @Test
    void parseExceptionIsAnLLMException() {
        assertInstanceOf(LLMException.class, assertThrows(ParseException.class, () -> parser.parsePlaylistSpec("x")));
    }

    // ---- parseSearchQuery ----

    @Test
    void parsesASearchQuery() {
        SearchQuery q = parser.parseSearchQuery("```\n{\"keywords\":\"rain\",\"genre\":\"jazz\","
                + "\"filters\":{\"year\":\"1999\"},\"confidence\":0.9}\n```");
        assertEquals("rain", q.keywords());
        assertEquals("jazz", q.genre());
        assertEquals(Map.of("year", "1999"), q.filters());
        assertEquals(0.9, q.confidence(), 1e-9);
        assertEquals("rain jazz year:1999", q.toSpotifyQuery());
    }

    @Test
    void filtersOutsideTheAllowedListAreDropped() {
        SearchQuery q = parser.parseSearchQuery("{\"keywords\":\"rain\",\"filters\":{\"mood\":\"sad\","
                + "\"year\":\"1999\",\"genre\":\"jazz\",\"artist\":\"Nina\"},\"confidence\":0.9}");
        assertEquals(Map.of("year", "1999", "artist", "Nina"), q.filters());
        assertEquals("rain year:1999 artist:Nina", q.toSpotifyQuery());
    }

    @Test
    void filterKeysAreStoredLowercased() {
        SearchQuery q = parser.parseSearchQuery("{\"keywords\":\"rain\",\"filters\":{\"Year\":\"1999\"},\"confidence\":0.9}");
        assertEquals("rain year:1999", q.toSpotifyQuery());
    }

    @Test
    void aQueryWhoseOnlyFilterIsUnknownIsRejectedAsEmpty() {
        assertThrows(ParseException.class,
                () -> parser.parseSearchQuery("{\"keywords\":\"\",\"filters\":{\"mood\":\"sad\"}}"));
    }

    @Test
    void searchConfidenceIsClampedAndEmptyQueriesRejected() {
        assertEquals(1.0, parser.parseSearchQuery("{\"keywords\":\"x\",\"confidence\":4}").confidence(), 1e-9);
        assertThrows(ParseException.class, () -> parser.parseSearchQuery("{\"keywords\":\"  \",\"genre\":\"\"}"));
        assertThrows(ParseException.class, () -> parser.parseSearchQuery("{oops"));
    }

    // ---- parseCommandSpecs ----

    @Test
    void parsesCommandSpecs() {
        List<CommandSpec> specs = parser.parseCommandSpecs("{\"commands\":["
                + "{\"type\":\"remove\",\"track\":\"2\"},"
                + "{\"type\":\"ADD\",\"query\":\"mellow piano\"},"
                + "{\"type\":\"replace\",\"track\":\"3\",\"query\":\"upbeat\"},"
                + "{\"type\":\"reorder\",\"track\":\"1\",\"position\":-4}]}");
        assertEquals(4, specs.size());
        assertEquals(new CommandSpec(CommandSpec.Type.REMOVE, "2", null, null), specs.get(0));
        assertEquals(new CommandSpec(CommandSpec.Type.ADD, null, "mellow piano", null), specs.get(1));
        assertEquals(new CommandSpec(CommandSpec.Type.REPLACE, "3", "upbeat", null), specs.get(2));
        assertEquals(new CommandSpec(CommandSpec.Type.REORDER, "1", null, 0), specs.get(3));
    }

    @Test
    void invalidCommandSpecsAreRejected() {
        for (String bad : new String[]{
                "{\"commands\":[]}", "{}", "{\"commands\":[{\"type\":\"explode\",\"track\":\"1\"}]}",
                "{\"commands\":[{\"type\":\"remove\"}]}", "{\"commands\":[{\"type\":\"add\"}]}",
                "{\"commands\":[{\"type\":\"reorder\",\"track\":\"1\"}]}", "{\"commands\":[\"remove\"]}"}) {
            assertThrows(ParseException.class, () -> parser.parseCommandSpecs(bad), bad);
        }
    }

    // ---- parseOptions / parseText ----

    @Test
    void parsesOptionsAndPlainText() {
        assertEquals(List.of("a", "b"), parser.parseOptions("{\"options\":[\"a\",\" b \",\"\"]}"));
        assertThrows(ParseException.class, () -> parser.parseOptions("{\"options\":[]}"));
        assertEquals("Hello there.", parser.parseText("  Hello there.\n"));
        assertThrows(ParseException.class, () -> parser.parseText("   "));
        assertThrows(ParseException.class, () -> parser.parseText("{\"a\":1}"));
    }

    // ---- verifyTracks ----

    @Test
    void verifyTracksKeepsOnlyTracksConfirmedByResults() {
        List<Track> proposed = List.of(track("real1"), track("ghost"), track("real2"), track("real1"));
        List<Track> results = List.of(track("real2"), track("real1"), track("other"));
        assertEquals(List.of(track("real1"), track("real2")), parser.verifyTracks(proposed, results));
        assertTrue(parser.verifyTracks(null, results).isEmpty());
        assertTrue(parser.verifyTracks(proposed, null).isEmpty());
    }

    // ---- verifyExplanation ----

    private static final DecisionTrace TRACE = new DecisionTrace("Balanced",
            Map.of("t1", "familiar", "t2", "new artist"), List.of("no explicit"));

    @Test
    void acceptsExplanationsThatNameOnlyTraceIds() {
        String text = "  Picked [t1] because it is familiar and [t2] to explore.  ";
        assertEquals(text.trim(), parser.verifyExplanation(text, TRACE));
        assertEquals("A plain balance of favourites.", parser.verifyExplanation("A plain balance of favourites.", TRACE));
    }

    @Test
    void rejectsExplanationsNamingIdsAbsentFromTheTrace() {
        assertThrows(ParseException.class, () -> parser.verifyExplanation("I chose [t9] too.", TRACE));
        assertThrows(ParseException.class,
                () -> parser.verifyExplanation("See spotify:track:6rqhFgbbKwnb9MLmUQDhG6 here.", TRACE));
        assertThrows(ParseException.class,
                () -> parser.verifyExplanation("Includes 6rqhFgbbKwnb9MLmUQDhG6 as well.", TRACE));
        assertThrows(ParseException.class, () -> parser.verifyExplanation("   ", TRACE));
        assertThrows(ParseException.class, () -> parser.verifyExplanation("[t1] ok", null));
    }

    private static final DecisionTrace GENRE_TRACE = new DecisionTrace("DiscoveryStrategy",
            Map.of("t1", "found by searching your \"jazz\" genre", "t2", "found by searching your \"jazz\" genre",
                    "t3", "from your own listening history"),
            List.of("total length within 10% of the 60 minute target", "no duplicate tracks"));

    @Test
    void acceptsProseUsingOnlyGenresNumbersAndQuotesFromTheTrace() {
        String text = "Three picks, [t1] and [t2], came from your \"jazz\" genre, [t3] from your history, "
                + "and the total is within 10% of 60 minutes. 2 tracks matched jazz.";
        assertEquals(text, parser.verifyExplanation(text, GENRE_TRACE));
    }

    @Test
    void rejectsAGenreThatIsNotInTheTrace() {
        assertThrows(ParseException.class,
                () -> parser.verifyExplanation("These are [t1] and other classical picks.", GENRE_TRACE));
    }

    private static final DecisionTrace GOAL_TRACE = new DecisionTrace("BalancedStrategy",
            Map.of("t1", "found by searching the \"dance\" genre you asked for", "t2", "by an artist you already listen to"),
            List.of("built around the genres you asked for: dance, edm"), List.of("dance", "edm"));

    @Test
    void rejectsClaimsOfUserPreferenceThatTheTraceDoesNotRecord() {
        for (String claim : List.of("It uses the dance and edm genres you love.", "Tracks like [t1] fit what you enjoy.",
                "You like dance, so [t1] was picked.", "These are your favorite genres: dance.",
                "It matches the genres you've always loved.")) {
            assertThrows(ParseException.class, () -> parser.verifyExplanation(claim, GOAL_TRACE), claim);
        }
    }

    @Test
    void acceptsTheSameFactsWordedAsTheUsersRequestAndAsListeningHistory() {
        String text = "[t1] came from the dance genre you asked for and [t2] is by an artist you already listen to.";
        assertEquals(text, parser.verifyExplanation(text, GOAL_TRACE));
    }

    @Test
    void rejectsANumberThatIsNotInTheTrace() {
        assertThrows(ParseException.class,
                () -> parser.verifyExplanation("All 12 tracks come from your jazz genre.", GENRE_TRACE));
        assertThrows(ParseException.class,
                () -> parser.verifyExplanation("The playlist is 45 minutes long.", GENRE_TRACE));
    }

    @Test
    void rejectsAQuotedNameThatIsNotInTheTrace() {
        assertThrows(ParseException.class,
                () -> parser.verifyExplanation("[t1] is \"Blue in Green\", a classic.", GENRE_TRACE));
    }
}
