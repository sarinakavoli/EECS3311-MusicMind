package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.SearchQuery;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private final Config config = Config.of(Map.of());

    private Planner planner(LLMProvider llm) {
        return new Planner(llm, new PromptBuilder(config), new ResponseParser(config), config);
    }

    @Test
    void aConfidentParseIsUsed() {
        SearchQuery q = planner(p -> "{\"keywords\":\"mellow study\",\"genre\":\"alternative\","
                + "\"filters\":{},\"confidence\":0.9}").parseSearchQuery("mellow alternative for studying");
        assertEquals("study chill", q.keywords(), "'mellow' is a mood word: replaced by its mapped genre");
        assertEquals("alternative", q.genre());
    }

    @Test
    void moodWordsAreKeptOutOfTheSpotifyQueryAndReplacedByTheMappedGenre() {
        SearchQuery q = planner(p -> "{\"keywords\":\"upbeat running\",\"genre\":\"\",\"filters\":{},\"confidence\":0.9}")
                .parseSearchQuery("upbeat songs for running");

        assertEquals("running", q.keywords());
        assertEquals(MoodGenreMap.load().genresFor("upbeat").get(0), q.genre());
        assertEquals("running " + q.genre(), q.toSpotifyQuery());
        assertFalse(q.toSpotifyQuery().contains("upbeat"));
    }

    @Test
    void whenTheModelAlreadyGaveAGenreTheMappedGenreJoinsTheKeywordsAndNoMoodWordRemains() {
        SearchQuery q = planner(p -> "{\"keywords\":\"upbeat indie Calm running\",\"genre\":\"indie\",\"filters\":{},"
                + "\"confidence\":0.9}").parseSearchQuery("upbeat indie songs for running");

        String query = q.toSpotifyQuery().toLowerCase();
        assertFalse(query.contains("upbeat") || query.contains("calm"), query);
        assertTrue(query.contains("indie") && query.contains("running"), query);
        assertEquals("indie", q.genre());
        assertTrue(q.keywords().toLowerCase().contains(MoodGenreMap.load().genresFor("upbeat").get(0)), q.keywords());
    }

    @Test
    void aQueryWithNoMoodWordIsLeftAsTheModelGaveIt() {
        SearchQuery q = planner(p -> "{\"keywords\":\"piano covers\",\"genre\":\"jazz\",\"filters\":{\"year\":\"1999\"},"
                + "\"confidence\":0.9}").parseSearchQuery("jazz piano covers from 1999");

        assertEquals("piano covers", q.keywords());
        assertEquals("jazz", q.genre());
        assertEquals("1999", q.filters().get("year"));
    }

    @Test
    void theRawKeywordFallbackStaysAsTypedMoodWordsIncluded() {
        SearchQuery q = planner(null).parseSearchQuery("upbeat indie songs for running");

        assertEquals("upbeat indie songs for running", q.keywords());
        assertEquals(0.0, q.confidence());
    }

    @Test
    void theSearchPromptAsksForArtistsGenresAndActivitiesNotMoodAdjectives() {
        String prompt = new PromptBuilder(config).buildSearchPrompt("upbeat indie songs for running");

        assertTrue(prompt.contains("mood"), prompt);
        assertTrue(prompt.contains("artist") && prompt.contains("genre"), prompt);
        assertTrue(prompt.contains("titles"), "says why: Spotify matches title words: " + prompt);
    }

    @Test
    void theLlmIsAskedWithASearchPrompt() {
        List<String> kinds = new ArrayList<>();
        planner(p -> {
            kinds.add(PromptBuilder.kindOf(p).orElseThrow().name());
            return "{\"keywords\":\"x\",\"confidence\":0.9}";
        }).parseSearchQuery("x");
        assertEquals(List.of("SEARCH"), kinds);
    }

    @Test
    void lowConfidenceFallsBackToTheOriginalText() {
        SearchQuery q = planner(p -> "{\"keywords\":\"guess\",\"confidence\":0.2}").parseSearchQuery("  rainy day jazz ");
        assertEquals("rainy day jazz", q.keywords());
        assertNull(q.genre());
        assertTrue(q.filters().isEmpty());
        assertTrue(q.confidence() < config.searchMinConfidence());
    }

    @Test
    void aMalformedReplyFallsBackToTheOriginalText() {
        assertEquals("rainy day jazz", planner(p -> "not json at all").parseSearchQuery("rainy day jazz").keywords());
        assertEquals("rainy day jazz", planner(p -> "{\"keywords\":\"\",\"confidence\":0.9}")
                .parseSearchQuery("rainy day jazz").keywords());
    }

    @Test
    void aReplyWithOnlyAnUnknownFilterFallsBackToTheOriginalText() {
        SearchQuery q = planner(p -> "{\"keywords\":\"\",\"filters\":{\"mood\":\"sad\"},\"confidence\":0.9}")
                .parseSearchQuery("sad songs");
        assertEquals("sad songs", q.keywords());
        assertTrue(q.filters().isEmpty());
    }

    @Test
    void anLlmFailureFallsBackToTheOriginalText() {
        assertEquals("lofi", planner(p -> { throw new LLMException("down"); }).parseSearchQuery("lofi").keywords());
        assertEquals("lofi", planner(p -> { throw new IllegalStateException("boom"); }).parseSearchQuery("lofi").keywords());
    }

    @Test
    void withNoLlmItIsAKeywordSearch() {
        SearchQuery q = planner(null).parseSearchQuery("lofi beats");
        assertEquals("lofi beats", q.keywords());
        assertEquals("lofi beats", q.toSpotifyQuery());
    }
}
