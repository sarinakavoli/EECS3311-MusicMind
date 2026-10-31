package com.musicmind.agent;

import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSummarizerNoProviderTest {

    private static ListeningProfile sample() {
        return new ListeningProfile(Map.of("pop", 0.6),
                List.of(new Artist("a1", "Artist One", List.of("pop"))), List.of(), Instant.now());
    }

    @Test
    void withoutAProviderTheSummaryIsComputedAndLabelled() {
        String summary = new ProfileSummarizer(null).summarize(sample());
        assertTrue(summary.contains("pop"), summary);
        assertTrue(summary.contains(ProfileSummarizer.COMPUTED_LABEL), summary);
    }

    @Test
    void aFailingProviderFallsBackWithTheSameLabel() {
        String summary = new ProfileSummarizer(p -> { throw new RuntimeException("down"); }).summarize(sample());
        assertTrue(summary.contains(ProfileSummarizer.COMPUTED_LABEL), summary);
    }

    @Test
    void anLlmSentenceIsNotLabelledAsComputed() {
        String summary = new ProfileSummarizer(p -> "You like pop.").summarize(sample());
        assertTrue(!summary.contains(ProfileSummarizer.COMPUTED_LABEL));
    }
}
