package com.musicmind.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockLLMProviderTest {

    private final MockLLMProvider mock = new MockLLMProvider();

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void planPromptYieldsAPlaylistSpecJson() {
        var o = json(mock.complete("Create a playlist plan for: 90 minutes of focus music")).getAsJsonObject();
        assertTrue(o.get("targetMinutes").getAsInt() > 0);
        assertTrue(o.has("allowExplicit") && o.has("familiarRatio") && o.has("blockedArtists"));
    }

    @Test
    void searchPromptYieldsASearchQueryJson() {
        var o = json(mock.complete("Parse this search request: upbeat songs")).getAsJsonObject();
        assertTrue(o.has("text"));
    }

    @Test
    void editPromptYieldsACommandList() {
        var o = json(mock.complete("Modify the playlist: remove the slowest track")).getAsJsonObject();
        assertTrue(o.getAsJsonArray("commands").size() > 0);
    }

    @Test
    void optionsPromptYieldsTextOptions() {
        var o = json(mock.complete("The constraints are infeasible. Suggest options")).getAsJsonObject();
        assertTrue(o.getAsJsonArray("options").size() >= 2);
    }

    @Test
    void trendsExplainAndSummaryPromptsYieldPlainText() {
        for (String p : new String[]{"Describe the listening trends", "Explain these decisions", "Summarise this taste profile"}) {
            String out = mock.complete(p);
            assertFalse(out.isBlank());
            assertFalse(out.startsWith("{"), p);
        }
    }

    @Test
    void responsesAreDeterministic() {
        assertEquals(mock.complete("Explain these decisions"), mock.complete("Explain these decisions"));
    }
}
