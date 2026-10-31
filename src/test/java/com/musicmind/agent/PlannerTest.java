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
        assertEquals("mellow study", q.keywords());
        assertEquals("alternative", q.genre());
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
