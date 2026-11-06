package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.RangeChange;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void explainPrompt_forbidsClaimingCandidatesFitTheGoal() {
        String p = builder.buildExplainPrompt(new DecisionTrace("Balanced", Map.of("t1", "you added it from search"), List.of()));
        assertTrue(p.contains("added by the user"), p);
        assertTrue(p.contains("fits a mood") && p.contains("reason says so"), p);
    }

    @Test
    void explainPromptSaysTheRequestedGenresCameFromTheGoalNotFromTheUsersTaste() {
        DecisionTrace trace = new DecisionTrace("Balanced", Map.of("t1", "matches dance"), List.of(), List.of("dance", "edm"));
        String p = builder.buildExplainPrompt(trace);
        assertTrue(p.contains("dance, edm"), p);
        assertTrue(p.contains("asked for") && p.contains("not") && p.contains("taste"), p);
        assertTrue(p.contains("came from the goal, the mood or the style requested"), p);
        assertFalse(p.contains("Say the listener asked"), p);
        assertFalse(builder.buildExplainPrompt(new DecisionTrace("Balanced", Map.of("t1", "x"), List.of()))
                .contains("asked for in the goal"));
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
    void trendPromptStatesGenreCoverageAsAFactAndSaysToCallItPartialWhenLow() {
        Map<String, List<Artist>> artists = Map.of(
                "short", List.of(new Artist("a", "A", List.of("rock")), new Artist("b", "B", List.of("rock"))),
                "long", List.of(new Artist("c", "C", List.of()), new Artist("d", "D", List.of())));
        TrendReport low = new TrendReport(artists, Map.of(), List.of("rock"), List.of(), List.of(), List.of(), List.of(), null);

        String p = builder.buildTrendPrompt(low);

        assertTrue(p.contains("short: 2 of 2") && p.contains("long: 0 of 2"), p);
        assertTrue(p.contains("partial"), "the model is told coverage is partial: " + p);
    }

    @Test
    void trendPromptWithFullCoverageDoesNotCallItPartial() {
        Map<String, List<Artist>> artists = Map.of("short", List.of(new Artist("a", "A", List.of("rock"))),
                "long", List.of(new Artist("c", "C", List.of("jazz"))));
        TrendReport full = new TrendReport(artists, Map.of(), List.of("rock"), List.of("jazz"), List.of(), List.of(), List.of(), null);

        String p = builder.buildTrendPrompt(full);

        assertTrue(p.contains("short: 1 of 1"), p);
        assertFalse(p.contains("partial"), p);
    }

    @Test
    void editPrompt_listsTagsAndKind_noTitles() {
        Map<String, com.musicmind.domain.SongTags> tags = Map.of(
                "idA", new com.musicmind.domain.SongTags(List.of("sad", "mellow"), com.musicmind.domain.SongTags.Kind.SONG),
                "idB", new com.musicmind.domain.SongTags(List.of("rock"), com.musicmind.domain.SongTags.Kind.ARTIST),
                "idC", new com.musicmind.domain.SongTags(List.of(), com.musicmind.domain.SongTags.Kind.NONE));

        String p = builder.buildEditPrompt("remove the sad ones", editContext("idA", "idB", "idC"), tags);

        assertTrue(p.contains("1: idA, clean, 3.0 min, song tags: sad, mellow"), p);
        assertTrue(p.contains("2: idB, explicit, 3.0 min, artist tags: rock"), p);
        assertTrue(p.contains("3: idC, clean, 3.0 min, no tags"), p);
        assertTrue(p.contains("never guess for a song with no tags"), p);
        assertFalse(p.contains("Title"), p);
        assertFalse(builder.buildEditPrompt("x", editContext("idA")).contains("tags"));
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
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            tracks.add(new Track(ids[i], "spotify:track:" + ids[i], "Title " + ids[i], 180_000,
                    i == 1, List.of()));
        }
        return new PlaylistContext("pl", tracks, null);
    }

    @Test
    void anEditPromptForAPlaylistLongerThanTheCapIsRefusedAndOneAtTheCapIsBuilt() {
        int cap = Config.of(Map.of()).editMaxPromptTracks();
        String[] ids = new String[cap + 1];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "t" + i;
        }
        assertThrows(IllegalArgumentException.class, () -> builder.buildEditPrompt("x", editContext(ids)));
        String[] atCap = Arrays.copyOf(ids, cap);
        assertTrue(builder.buildEditPrompt("x", editContext(atCap)).contains(cap + ": t" + (cap - 1)));
    }

    @Test
    void theTrendPromptGivesEachStepBetweenRanges() {
        TrendReport r = new TrendReport(Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new RangeChange("long", "medium", List.of("folk"), List.of("jazz"), List.of("a"), List.of())));

        String prompt = new PromptBuilder(Config.of(Map.of())).buildTrendPrompt(r);

        assertTrue(prompt.contains("long to medium"), prompt);
        assertTrue(prompt.contains("folk") && prompt.contains("jazz"), prompt);
    }

    private static final String MARK_TITLE = "Zyxwv Distinct Title";
    private static final String MARK_ARTIST = "Qwertz Distinct Artist";

    private static Track marked(String id) {
        return new Track(id, "spotify:track:" + id, MARK_TITLE + " " + id, 180_000, false, List.of("art" + id),
                List.of(MARK_ARTIST + " " + id));
    }

    @Test
    void goalReviewPrompt_containsNoTitlesOrArtistNames() {
        List<Track> tracks = List.of(marked("4uLU6hMC"), marked("7abc"));
        Map<String, List<String>> genres = Map.of("4uLU6hMC", List.of("hip hop", "rap"), "7abc", List.of("jazz"));

        String prompt = builder.buildGoalReviewPrompt("a workout mix", tracks, t -> genres.get(t.id()));

        assertFalse(prompt.contains(MARK_TITLE), prompt);
        assertFalse(prompt.contains(MARK_ARTIST), prompt);
        assertFalse(prompt.contains("Zyxwv") || prompt.contains("Qwertz"), prompt);
        assertTrue(prompt.contains("4uLU6hMC | hip hop, rap\n"), prompt);
        assertTrue(prompt.contains("7abc | jazz\n"), prompt);
        assertTrue(prompt.contains("a workout mix"), prompt);
        assertTrue(prompt.contains("only the genres") && prompt.contains("keep it when unsure"), prompt);
        assertFalse(prompt.contains("generic or functional"), prompt);
    }

    @Test
    void noPromptBuilderPromptContainsATrackTitleOrArtistName() throws Exception {
        Track t = marked("t1");
        Artist artist = new Artist("art1", MARK_ARTIST, List.of("pop"));
        ListeningProfile profile = new ListeningProfile(Map.of("pop", 1.0), List.of(artist), List.of(t),
                List.of("t1"), null, Instant.now(), false, List.of());
        TrendReport trend = new TrendReport(Map.of("short", List.of(artist), "long", List.of(artist)),
                Map.of("short", Map.of("pop", 1.0)), List.of("pop"), List.of("jazz"), List.of("art1"), List.of(),
                List.of(), null, List.of(new RangeChange("long", "short", List.of("pop"), List.of(), List.of("art1"), List.of())));
        PlaylistContext playlist = new PlaylistContext("pl", List.of(t, marked("t2")), null);
        // plan and search take only the user's own words and MusicMind constraints, no Spotify object; the other
        // builders get the objects that can carry Spotify content (tracks, artists), filled with the markers.
        Map<String, java.util.function.Supplier<String>> calls = Map.of(
                "buildPlanPrompt", () -> builder.buildPlanPrompt(new AgentRequest("rainy day mix", RequestKind.CREATE_PLAYLIST,
                        new PlaylistSpec(45, false, 0.8, List.of("art1"), null))),
                "buildGoalReviewPrompt", () -> builder.buildGoalReviewPrompt("workout", List.of(t), x -> List.of("pop")),
                "buildExplainPrompt", () -> builder.buildExplainPrompt(
                        new DecisionTrace("Balanced", Map.of("t1", "matches pop"), List.of("no explicit"))),
                "buildSummaryPrompt", () -> builder.buildSummaryPrompt(profile),
                "buildTrendPrompt", () -> builder.buildTrendPrompt(trend),
                "buildSearchPrompt", () -> builder.buildSearchPrompt("jazz"),
                "buildEditPrompt", () -> builder.buildEditPrompt("remove the second", playlist,
                        Map.of("t1", new com.musicmind.domain.SongTags(List.of("sad"), com.musicmind.domain.SongTags.Kind.SONG),
                                "t2", new com.musicmind.domain.SongTags(List.of(), com.musicmind.domain.SongTags.Kind.NONE))),
                "buildOptionsPrompt", () -> builder.buildOptionsPrompt(
                        new FeasibilityReport(false, List.of("too short"), List.of("allow more explicit"))));

        java.util.Set<String> built = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : PromptBuilder.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getName().startsWith("build")) {
                built.add(m.getName());
            }
        }
        assertEquals(new java.util.TreeSet<>(calls.keySet()), built,
                "every public build* method of PromptBuilder must be covered by this leak guard");
        calls.forEach((name, call) -> {
            String p = call.get();
            assertFalse(p.contains("Zyxwv") || p.contains("Qwertz"), name + ": " + p);
        });
    }
}
