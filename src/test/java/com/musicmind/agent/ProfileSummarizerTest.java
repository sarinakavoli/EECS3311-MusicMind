package com.musicmind.agent;

import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.llm.LLMProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the summary uses the LLM when usable and falls back deterministically otherwise. */
class ProfileSummarizerTest {

    private static ListeningProfile sample() {
        return new ListeningProfile(
                Map.of("pop", 0.6, "rock", 0.4),
                List.of(new Artist("a1", "Artist One", List.of("pop"))),
                List.of(),
                Instant.now());
    }

    @Test
    void usesLlmSentenceWhenUsable() {
        LLMProvider llm = prompt -> "You love upbeat pop with a rock edge.";
        assertEquals("You love upbeat pop with a rock edge.",
                new ProfileSummarizer(llm).summarize(sample()));
    }

    @Test
    void stripsWrappingQuotesAndTakesFirstLine() {
        LLMProvider llm = prompt -> "\"A pop-leaning listener.\"\nextra line";
        assertEquals("A pop-leaning listener.", new ProfileSummarizer(llm).summarize(sample()));
    }

    @Test
    void fallsBackWhenLlmThrows() {
        LLMProvider llm = prompt -> { throw new RuntimeException("network down"); };
        String summary = new ProfileSummarizer(llm).summarize(sample());
        assertTrue(summary.toLowerCase().contains("pop"), "fallback should mention the top genre");
    }

    @Test
    void fallsBackWhenLlmReturnsJson() {
        LLMProvider llm = prompt -> "{}";
        assertFalse(new ProfileSummarizer(llm).summarize(sample()).startsWith("{"));
    }

    @Test
    void handlesEmptyProfile() {
        LLMProvider llm = prompt -> "unused";
        String summary = new ProfileSummarizer(llm)
                .summarize(new ListeningProfile(Map.of(), List.of(), List.of(), Instant.now()));
        assertTrue(summary.toLowerCase().contains("not enough"));
    }
}
