package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.TrendReport;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every prompt kind the builder produces must be answered by the mock in a form the parser accepts. */
class PromptRoundTripTest {

    private final Config config = Config.of(Map.of());
    private final PromptBuilder builder = new PromptBuilder(config);
    private final ResponseParser parser = new ResponseParser(config);
    private final MockLLMProvider llm = new MockLLMProvider();

    @Test
    void planPromptRoundTrips() {
        var spec = parser.parsePlaylistSpec(
                llm.complete(builder.buildPlanPrompt(new AgentRequest("focus", RequestKind.CREATE_PLAYLIST))));
        assertTrue(spec.targetMinutes() > 0);
    }

    @Test
    void searchPromptRoundTrips() {
        assertFalse(parser.parseSearchQuery(llm.complete(builder.buildSearchPrompt("upbeat"))).toSpotifyQuery().isBlank());
    }

    @Test
    void editPromptRoundTrips() {
        assertFalse(parser.parseCommandSpecs(llm.complete(builder.buildEditPrompt("drop one", new PlaylistContext("pl", List.of(new com.musicmind.domain.Track("t1", "spotify:track:t1", "T", 1000, false, List.of())), null)))).isEmpty());
    }

    @Test
    void optionsPromptRoundTrips() {
        String out = llm.complete(builder.buildOptionsPrompt(new FeasibilityReport(false, List.of("c"), List.of("o"))));
        assertTrue(parser.parseOptions(out).size() >= 2);
    }

    @Test
    void explainPromptRoundTripsThroughVerification() {
        DecisionTrace trace = new DecisionTrace("Balanced", Map.of("t1", "r"), List.of("c"));
        assertFalse(parser.verifyExplanation(llm.complete(builder.buildExplainPrompt(trace)), trace).isBlank());
    }

    @Test
    void summaryAndTrendPromptsRoundTrip() {
        var profile = new ListeningProfile(Map.of("pop", 1.0), List.of(), List.of(), Instant.EPOCH);
        assertFalse(parser.parseText(llm.complete(builder.buildSummaryPrompt(profile))).isBlank());
        var trend = new TrendReport(null, Map.of(), null, null, null, null, null, null);
        assertFalse(parser.parseText(llm.complete(builder.buildTrendPrompt(trend))).isBlank());
    }

    @Test
    void routingUsesTheMarkerNotKeywordsInTheUserText() {
        // The user text contains words that used to misroute ("explain", "trend", "option").
        String out = llm.complete(builder.buildSearchPrompt("explain the trend option"));
        assertFalse(parser.parseSearchQuery(out).toSpotifyQuery().isEmpty());
    }

    @Test
    void anUnmarkedPromptIsRejectedRatherThanMisrouted() {
        assertThrows(LLMException.class, () -> llm.complete("Create a playlist plan for: focus"));
    }
}
