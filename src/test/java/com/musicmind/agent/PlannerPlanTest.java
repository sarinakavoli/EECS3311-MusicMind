package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentPlan;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.UserPreference;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPlanTest {

    private final Config config = Config.of(Map.of());
    private static final String PLAN = "{\"targetMinutes\":60,\"allowExplicit\":true,\"familiarRatio\":0.7,"
            + "\"blockedArtists\":[\"invented\"],\"name\":\"Rainy\",\"confidence\":0.9,\"mood\":\"chill\"}";

    private Planner planner(LLMProvider llm) {
        return new Planner(llm, new PromptBuilder(config), new ResponseParser(config), config);
    }

    private static AgentRequest request(String text, PlaylistSpec constraints) {
        return new AgentRequest(text, RequestKind.CREATE_PLAYLIST, constraints);
    }

    @Test
    void theGoalBecomesASpecAndTheLlmIsAskedWithAPlanPrompt() {
        List<String> kinds = new ArrayList<>();
        AgentPlan plan = planner(p -> {
            kinds.add(PromptBuilder.kindOf(p).orElseThrow().name());
            return PLAN;
        }).plan(request("rainy day chill", null));
        assertEquals(List.of("PLAN"), kinds);
        assertEquals(60, plan.spec().targetMinutes());
        assertEquals(0.7, plan.spec().familiarRatio());
        assertEquals("Rainy", plan.spec().name());
        assertFalse(plan.steps().isEmpty());
        assertEquals(60, planner(p -> PLAN).parseGoal("rainy day chill").targetMinutes());
    }

    @Test
    void artistIdsTheLlmInventsAreIgnored() {
        AgentPlan plan = planner(p -> PLAN).plan(request("x", null));
        assertTrue(plan.spec().blockedArtists().isEmpty());
    }

    @Test
    void theMoodBecomesGenresFromTheCuratedMapThenPreferredStyles() {
        UserPreference styles = new UserPreference("preferredStyles", "jazz,ambient", false, 0.5);
        AgentPlan plan = planner(p -> PLAN).plan(request("x", null), List.of(styles));
        // chill -> chill, ambient, downtempo, lo-fi, acoustic; styles jazz, ambient; duplicates dropped; capped.
        assertEquals(List.of("chill", "ambient", "downtempo", "lo-fi"), plan.seedGenres());
        AgentPlan noMood = planner(p -> PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\"")).plan(request("x", null), List.of(styles));
        assertEquals(List.of("ambient", "jazz"), noMood.seedGenres());
        AgentPlan unknown = planner(p -> PLAN.replace("chill", "zzz")).plan(request("x", null));
        assertTrue(unknown.seedGenres().isEmpty());
    }

    @Test
    void explicitConstraintsOverrideTheLlmAndExplicitContentOnlyGetsStricter() {
        PlaylistSpec flags = new PlaylistSpec(30, false, 0.2, List.of("blk"), "Mine");
        PlaylistSpec spec = planner(p -> PLAN).plan(request("x", flags)).spec();
        assertEquals(30, spec.targetMinutes());
        assertEquals(0.2, spec.familiarRatio());
        assertEquals("Mine", spec.name());
        assertEquals(List.of("blk"), spec.blockedArtists());
        assertFalse(spec.allowExplicit());
        // Flags that were not given (0 minutes, no ratio, no name) leave the LLM's values alone.
        PlaylistSpec unset = new PlaylistSpec(0, true, Double.NaN, List.of(), null);
        PlaylistSpec kept = planner(p -> PLAN).plan(request("x", unset)).spec();
        assertEquals(60, kept.targetMinutes());
        assertEquals(0.7, kept.familiarRatio());
        assertEquals("Rainy", kept.name());
        assertTrue(kept.allowExplicit());
    }

    @Test
    void storedPreferencesAreMerged() {
        List<UserPreference> prefs = List.of(
                new UserPreference("blockedArtists", "b1,b2", false, 0.5),
                new UserPreference("explicitPolicy", "block", false, 0.5),
                new UserPreference("familiarBias", "0.2", false, 0.2));
        PlaylistSpec spec = planner(p -> PLAN).plan(request("x", null), prefs).spec();
        assertEquals(java.util.Set.of("b1", "b2"), java.util.Set.copyOf(spec.blockedArtists()));
        assertFalse(spec.allowExplicit());
        // The request stated 0.7, so the stored bias does not replace it ...
        assertEquals(0.7, spec.familiarRatio());
        // ... but it fills in when the request said nothing.
        String silent = "{\"targetMinutes\":60,\"allowExplicit\":true,\"name\":\"R\",\"confidence\":0.9}";
        assertEquals(0.2, planner(p -> silent).plan(request("x", null), prefs).spec().familiarRatio());
        assertEquals(config.parserDefaultFamiliarRatio(),
                planner(p -> silent).plan(request("x", null)).spec().familiarRatio());
    }

    @Test
    void lowConfidenceAndMalformedRepliesAskTheUserToClarify() {
        ClarificationNeededException low = assertThrows(ClarificationNeededException.class,
                () -> planner(p -> PLAN.replace("0.9", "0.2")).plan(request("hmm", null)));
        assertTrue(low.getMessage().contains("hmm"));
        assertThrows(ClarificationNeededException.class, () -> planner(p -> "I cannot do that").plan(request("hmm", null)));
        assertThrows(ClarificationNeededException.class, () -> planner(p -> PLAN).plan(request("   ", null)));
        assertThrows(ClarificationNeededException.class, () -> planner(p -> PLAN).parseGoal(""));
    }

    @Test
    void outOfRangeFlagsAreRejectedNotClamped() {
        PlaylistSpec tooLong = new PlaylistSpec(config.parserMaxTargetMinutes() + 1, true, Double.NaN, List.of(), null);
        assertThrows(ClarificationNeededException.class, () -> planner(p -> PLAN).plan(request("x", tooLong)));
        PlaylistSpec badRatio = new PlaylistSpec(0, true, 1.5, List.of(), null);
        assertThrows(ClarificationNeededException.class, () -> planner(p -> PLAN).plan(request("x", badRatio)));
    }

    @Test
    void withoutAnLlmPlanningSaysWhatIsNeeded() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> planner(null).plan(request("x", null)));
        assertTrue(e.getMessage().contains("LLM_API_KEY"), e.getMessage());
    }

    @Test
    void anLlmFailureIsReportedNotTurnedIntoAClarification() {
        LLMException e = assertThrows(LLMException.class, () -> planner(p -> {
            throw new LLMException("HTTP 500 raw body");
        }).plan(request("x", null)));
        assertEquals(config.wording("planLlmFailed"), e.getMessage());
    }

    @Test
    void regeneratingAppliesTheChosenRelaxationAndKeepsTheSeeds() {
        Planner planner = planner(p -> PLAN);
        AgentPlan plan = planner.plan(request("x", null));
        Relaxation shorten = new Relaxation(Relaxation.Kind.SHORTEN_DURATION, 30, "Shorten to 30 minutes");
        AgentPlan again = planner.regenerate(plan, shorten);
        assertEquals(30, again.spec().targetMinutes());
        assertEquals(plan.seedGenres(), again.seedGenres());
        assertEquals(plan.steps().size() + 1, again.steps().size());
        assertEquals(60, plan.spec().targetMinutes());
    }

    @Test
    void optionsArePhrasedByTheLlmOnlyWhenItKeepsTheCountAndOrder() {
        Relaxation a = new Relaxation(Relaxation.Kind.SHORTEN_DURATION, 30, "Shorten to 30 minutes");
        Relaxation b = new Relaxation(Relaxation.Kind.ALLOW_EXPLICIT, 1, "Allow explicit tracks");
        FeasibilityReport report = FeasibilityReport.infeasible(List.of("too short"), List.of(a, b));
        assertEquals(List.of("A shorter list", "With explicit songs"),
                planner(p -> "{\"options\":[\"A shorter list\",\"With explicit songs\"]}").phraseOptions(report));
        assertEquals(report.relaxationOptions(), planner(p -> "{\"options\":[\"only one\"]}").phraseOptions(report));
        assertEquals(report.relaxationOptions(), planner(p -> "nonsense").phraseOptions(report));
        assertEquals(report.relaxationOptions(), planner(null).phraseOptions(report));
    }

    @Test
    void plan_readsSearchTerms() {
        List<String> prompts = new ArrayList<>();
        AgentPlan plan = planner(p -> {
            prompts.add(p);
            return PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\",\"searchTerms\":[\" gym workout \",\"running\",\"gym workout\",\"\"]");
        }).plan(request("a workout mix", null));

        assertEquals(List.of("gym workout", "running"), plan.searchTerms());
        assertTrue(prompts.get(0).contains("searchTerms"), prompts.get(0));
        assertTrue(prompts.get(0).contains("search phrases"), prompts.get(0));
        assertTrue(prompts.get(0).contains("Do not name or choose any songs or artists"), prompts.get(0));
        assertFalse(prompts.get(0).toLowerCase().contains("names of songs"), prompts.get(0));
        assertTrue(planner(p -> PLAN).plan(request("x", null)).searchTerms().isEmpty());
    }

    @Test
    void plan_capsSearchTerms() {
        AgentPlan plan = planner(p -> PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\",\"searchTerms\":[\"a\",\"b\",\"c\",\"d\",\"e\"]"))
                .plan(request("x", null));

        assertEquals(List.of("a", "b", "c"), plan.searchTerms());
        assertEquals(List.of("a", "b"), new Planner(p -> PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\",\"searchTerms\":[\"a\",\"b\",\"c\"]"),
                new PromptBuilder(config), new ResponseParser(config.withTunables(Map.of("goal.maxSearchTerms", "2"))),
                config.withTunables(Map.of("goal.maxSearchTerms", "2"))).plan(request("x", null)).searchTerms());
    }

    @Test
    void aRegeneratedPlanKeepsItsSearchTerms() {
        AgentPlan plan = planner(p -> PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\",\"searchTerms\":[\"gym\"]")).plan(request("x", null));
        Relaxation chosen = new Relaxation(Relaxation.Kind.ALLOW_EXPLICIT, 1, "x");
        assertEquals(List.of("gym"), planner(p -> PLAN).regenerate(plan, chosen).searchTerms());
    }

    @Test
    void plan_readsTags() {
        List<String> prompts = new ArrayList<>();
        AgentPlan plan = planner(p -> {
            prompts.add(p);
            return PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\",\"tags\":[\" Slow Dance \",\"romantic\",\"slow dance\",\"\",\"third\"]");
        }).plan(request("music for a slow dance", null));

        assertEquals(List.of("Slow Dance", "romantic"), plan.tags()); // trimmed, no repeats, capped at lastfm.maxTags
        assertTrue(prompts.get(0).contains("\"tags\""), prompts.get(0));
        assertTrue(prompts.get(0).contains("Last.fm"), prompts.get(0));
        assertTrue(planner(p -> PLAN).plan(request("x", null)).tags().isEmpty());
    }

    @Test
    void aRegeneratedPlanKeepsItsTags() {
        AgentPlan plan = planner(p -> PLAN.replace("\"mood\":\"chill\"", "\"mood\":\"\",\"tags\":[\"study\"]")).plan(request("x", null));
        Relaxation chosen = new Relaxation(Relaxation.Kind.ALLOW_EXPLICIT, 1, "x");
        assertEquals(List.of("study"), planner(p -> PLAN).regenerate(plan, chosen).tags());
    }
}
