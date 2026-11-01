package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.TrendReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    private final Config config = Config.of(Map.of());
    private final PromptBuilder builder = new PromptBuilder(config);

    @Test
    void everyPromptStartsWithItsKindMarker() {
        assertEquals(Optional.of(PromptBuilder.Kind.PLAN),
                PromptBuilder.kindOf(builder.buildPlanPrompt(new AgentRequest("focus", RequestKind.CREATE_PLAYLIST))));
        assertEquals(Optional.of(PromptBuilder.Kind.SEARCH), PromptBuilder.kindOf(builder.buildSearchPrompt("jazz")));
        assertEquals(Optional.of(PromptBuilder.Kind.EDIT),
                PromptBuilder.kindOf(builder.buildEditPrompt("drop one", editContext("t1"))));
        assertEquals(Optional.empty(), PromptBuilder.kindOf("Create a playlist plan"));
        assertEquals(Optional.empty(), PromptBuilder.kindOf(null));
    }

    @Test
    void planPromptCarriesRequestTextAndExplicitConstraints() {
        PlaylistSpec flags = new PlaylistSpec(45, false, 0.8, List.of("blockedId"), null);
        String p = builder.buildPlanPrompt(new AgentRequest("rainy day mix", RequestKind.CREATE_PLAYLIST, flags));
        assertTrue(p.contains("rainy day mix"));
        assertTrue(p.contains("45"));
        assertTrue(p.contains("blockedId"));
        assertTrue(p.contains("targetMinutes") && p.contains("familiarRatio"));
    }

    @Test
    void explainPromptHasOnlyTraceFactsAndTellsTheModelToCiteIds() {
        DecisionTrace trace = new DecisionTrace("Balanced", Map.of("t1", "matches pop"), List.of("no explicit"));
        String p = builder.buildExplainPrompt(trace);
        assertTrue(p.contains("Balanced") && p.contains("t1") && p.contains("matches pop") && p.contains("no explicit"));
        assertTrue(p.contains("[id]"));
    }

    @Test
    void summaryPromptCarriesGenresAndCountsButNoNames() {
        Map<String, Double> hist = new LinkedHashMap<>();
        hist.put("jazz", 0.6);
        hist.put("pop", 0.4);
        ListeningProfile profile = new ListeningProfile(hist,
                List.of(new Artist("a1", "Secret Artist Name", List.of("jazz"))), List.of(), Instant.EPOCH);
        String p = builder.buildSummaryPrompt(profile);
        assertTrue(p.contains("jazz") && p.contains("pop"));
        assertFalse(p.contains("Secret Artist Name"));
    }

    @Test
    void genreListsAreCappedByConfiguration() {
        Map<String, Double> hist = new LinkedHashMap<>();
        for (int i = 0; i < config.promptMaxGenres() + 5; i++) {
            hist.put("genre" + String.format("%02d", i), 1.0 - i * 0.01);
        }
        String p = builder.buildSummaryPrompt(new ListeningProfile(hist, List.of(), List.of(), Instant.EPOCH));
        assertTrue(p.contains("genre00"));
        assertFalse(p.contains("genre" + String.format("%02d", config.promptMaxGenres())));
    }

    @Test
    void trendPromptCarriesGenreChangesAndGaps() {
        TrendReport r = new TrendReport(Map.of(), Map.of("short_term", Map.of("rock", 0.5)),
                List.of("techno"), List.of("folk"), List.of("x"), List.of("y", "z"), List.of("no long_term data"), null);
        String p = builder.buildTrendPrompt(r);
        assertTrue(p.contains("rock") && p.contains("techno") && p.contains("folk") && p.contains("no long_term data"));
    }

    @Test
    void searchEditAndOptionsPromptsCarryTheirInputs() {
        assertTrue(builder.buildSearchPrompt("sad 90s songs").contains("sad 90s songs"));
        String prompt = builder.buildSearchPrompt("sad 90s songs");
        assertTrue(prompt.contains("short"), prompt);
        assertTrue(prompt.contains("do not repeat the genre"), prompt);
        String edit = builder.buildEditPrompt("remove the second", editContext("idA", "idB"));
        assertTrue(edit.contains("remove the second") && edit.contains("1: idA") && edit.contains("2: idB"));
        assertTrue(edit.contains("1: idA, clean, 3.0 min"), edit);
        assertTrue(edit.contains("2: idB, explicit, 3.0 min"), edit);
        assertFalse(edit.contains("Title"), "no track titles go to the model");
        String opts = builder.buildOptionsPrompt(new FeasibilityReport(false, List.of("too short"), List.of("raise minutes")));
        assertTrue(opts.contains("too short") && opts.contains("raise minutes"));
    }

    @Test
    void summaryPromptForbidsGenreClaimsWhenThereAreNoGenres() {
        ListeningProfile profile = new ListeningProfile(new LinkedHashMap<>(), List.of(), List.of(), Instant.EPOCH);

        String p = builder.buildSummaryPrompt(profile);

        assertTrue(p.contains("Do not make any claims about genres or genre variety"), p);
    }

    @Test
    void summaryPromptDoesNotForbidGenreClaimsWhenGenresExist() {
        Map<String, Double> hist = new LinkedHashMap<>();
        hist.put("jazz", 1.0);

        String p = builder.buildSummaryPrompt(new ListeningProfile(hist, List.of(), List.of(), Instant.EPOCH));

        assertFalse(p.contains("Do not make any claims about genres"), p);
    }

    private static PlaylistContext editContext(String... ids) {
        List<com.musicmind.domain.Track> tracks = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            tracks.add(new com.musicmind.domain.Track(ids[i], "spotify:track:" + ids[i], "Title " + ids[i], 180_000,
                    i == 1, List.of()));
        }
        return new PlaylistContext("pl", tracks, null);
    }
}
