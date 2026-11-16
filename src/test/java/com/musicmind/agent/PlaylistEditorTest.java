package com.musicmind.agent;

import com.musicmind.agent.command.FakeMusicService;
import com.musicmind.agent.command.PlaylistCommand;
import com.musicmind.agent.command.RemoveTrackCommand;
import com.musicmind.agent.command.AddItemsCommand;
import com.musicmind.agent.command.ReplaceTrackCommand;
import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F6: turning an instruction into commands, including the mood heuristic, the questions and the failures. */
class PlaylistEditorTest {

    private final Config config = Config.of(Map.of());
    private final FakeMusicService music = new FakeMusicService();
    private final List<String> prompts = new ArrayList<>();

    private static Track track(String id, String artist) {
        return new Track(id, "spotify:track:" + id, "Title " + id, 180_000, false, List.of(artist));
    }

    private PlaylistEditor editor(LLMProvider llm) {
        PromptBuilder promptBuilder = new PromptBuilder(config);
        return new PlaylistEditor(llm == null ? null : p -> {
            prompts.add(p);
            return llm.complete(p);
        }, promptBuilder, new ResponseParser(config), MoodGenreMap.load(), config);
    }

    private PlaylistContext playlist(Track... tracks) {
        return new PlaylistContext("pl1", List.of(tracks), music);
    }

    @Test
    void commandsFromTheModelBecomeRealCommandsAndThePromptCarriesFactsNotTitles() {
        PlaylistEditor.EditPlan plan = editor(p -> "{\"commands\":[{\"type\":\"remove\",\"track\":\"2\"}]}")
                .plan(playlist(track("a", "x"), track("b", "y")), "drop the second", Map.of());
        assertEquals(1, plan.commands().size());
        assertInstanceOf(RemoveTrackCommand.class, plan.commands().get(0));
        assertFalse(plan.approximate());
        assertFalse(prompts.get(0).contains("Title"));
    }

    @Test
    void theTagsGivenToPlanReachThePrompt() {
        editor(p -> "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}").plan(
                playlist(track("a", "x"), track("b", "y")), "drop the sad one", Map.of(),
                Map.of("a", new com.musicmind.domain.SongTags(List.of("sad"), com.musicmind.domain.SongTags.Kind.SONG)));

        assertTrue(prompts.get(0).contains("1: a, clean, 3.0 min, song tags: sad"), prompts.get(0));
        assertTrue(prompts.get(0).contains("2: b, clean, 3.0 min, no tags"), prompts.get(0));
    }

    private static Track titled(String id, String title, String artist) {
        return new Track(id, "spotify:track:" + id, title, 180_000, false, List.of("a-" + id), List.of(artist));
    }

    private PlaylistContext namedPlaylist() {
        return playlist(titled("m1", "Vogue", "Madonna"), titled("m2", "Frozen (Radio Edit)", "Madonna"),
                titled("p1", "Come Clean", "Paramore"), titled("o1", "Other", "Someone"));
    }

    private static final String LLM_ANSWER = "{\"commands\":[{\"type\":\"remove\",\"track\":\"4\"}]}";

    @Test
    void removingAnArtistByNameIsDoneLocallyWithoutTheModel() {
        PlaylistContext pl = playlist(titled("m1", "Vogue", "Madonna"), titled("o1", "Other", "Someone"));
        PlaylistEditor.EditPlan plan = editor(p -> LLM_ANSWER).plan(pl, "remove Madonna,", Map.of());
        assertEquals(1, plan.commands().size());
        assertInstanceOf(RemoveTrackCommand.class, plan.commands().get(0));
        assertTrue(plan.commands().get(0).describe().contains("Vogue"), plan.commands().get(0).describe());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void removeAllSongsByAnArtistRemovesEveryTrackOfTheirs() {
        PlaylistEditor.EditPlan plan = editor(p -> LLM_ANSWER).plan(namedPlaylist(), "Remove all songs by Madonna", Map.of());
        assertEquals(2, plan.commands().size());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void removingByTitleMatchesTheTitleWithOrWithoutItsTail() {
        PlaylistEditor.EditPlan plan = editor(p -> LLM_ANSWER).plan(namedPlaylist(), "remove Come Clean", Map.of());
        assertEquals(1, plan.commands().size());
        assertTrue(plan.commands().get(0).describe().contains("Come Clean"));
        PlaylistEditor.EditPlan tail = editor(p -> LLM_ANSWER).plan(namedPlaylist(), "delete \"Frozen\"", Map.of());
        assertEquals(1, tail.commands().size());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void severalNamesAreAllRemoved() {
        PlaylistEditor.EditPlan plan = editor(p -> LLM_ANSWER).plan(namedPlaylist(), "remove Madonna and Paramore", Map.of());
        assertEquals(3, plan.commands().size());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void anUnknownNameOrADescriptionFallsThroughToTheModel() {
        PlaylistEditor.EditPlan unknown = editor(p -> LLM_ANSWER).plan(namedPlaylist(), "remove Unknown Artist", Map.of());
        assertEquals(1, unknown.commands().size());
        assertEquals(1, prompts.size());
        editor(p -> LLM_ANSWER).plan(namedPlaylist(), "remove the sad songs", Map.of());
        assertEquals(2, prompts.size());
        editor(p -> LLM_ANSWER).plan(namedPlaylist(), "remove Madonna and Nobody", Map.of());
        assertEquals(3, prompts.size());
    }

    @Test
    void withoutAnLlmEditingSaysSoInsteadOfGuessing() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> editor(null).plan(playlist(track("a", "x")), "drop it", Map.of()));
        assertEquals(config.wording("modifyNeedsLlm"), e.getMessage());
    }

    @Test
    void anLlmFailureGetsAShortMessageWithNoRawDetail() {
        LLMException e = assertThrows(LLMException.class, () -> editor(p -> {
            throw new LLMException("HTTP 500 https://api.example/v1 {\"error\":1}");
        }).plan(playlist(track("a", "x")), "drop it", Map.of()));
        assertEquals(config.wording("modifyLlmFailed"), e.getMessage());
    }

    @Test
    void anUnusableReplyOrAnUnknownReferenceAsksTheUserToRephrase() {
        for (String reply : List.of("not json at all", "{\"commands\":[{\"type\":\"remove\",\"track\":\"9\"}]}")) {
            ClarificationNeededException e = assertThrows(ClarificationNeededException.class,
                    () -> editor(p -> reply).plan(playlist(track("a", "x")), "drop nine", Map.of()));
            assertTrue(e.getMessage().contains("drop nine"));
        }
    }

    @Test
    void theModelsQuestionForAnAmbiguousInstructionIsPassedOnAndNothingIsChanged() {
        ClarificationNeededException e = assertThrows(ClarificationNeededException.class,
                () -> editor(p -> "{\"commands\":[],\"mood\":\"\",\"question\":\"Which tracks do you mean?\"}")
                        .plan(playlist(track("a", "x")), "fix it", Map.of()));
        assertEquals("Which tracks do you mean?", e.getMessage());
    }

    @Test
    void emptyCommandsWithNeitherMoodNorQuestionIsAskedToBeRephrased() {
        assertThrows(ClarificationNeededException.class,
                () -> editor(p -> "{\"commands\":[]}").plan(playlist(track("a", "x")), "hmm", Map.of()));
    }

    @Test
    void aMoodEditReplacesTracksWhoseKnownGenresDoNotFitAndIsFlaggedApproximate() {
        music.searchResults.put("ambient", List.of(track("n1", "z"), track("n2", "z")));
        PlaylistEditor.EditPlan plan = editor(p -> "{\"commands\":[],\"mood\":\"calm\"}").plan(
                playlist(track("a", "rockArtist"), track("b", "ambientArtist"), track("c", "unknownArtist")),
                "make it calmer",
                Map.of("rockArtist", List.of("rock"), "ambientArtist", List.of("Ambient")));
        assertTrue(plan.approximate());
        assertEquals("calm", plan.mood());
        assertEquals(1, plan.commands().size(), "only the track known not to fit is replaced");
        assertInstanceOf(ReplaceTrackCommand.class, plan.commands().get(0));
        assertTrue(plan.commands().get(0).describe().contains("n1"), plan.commands().get(0).describe());
    }

    @Test
    void whenNothingIsKnownToMisfitAMoodEditAddsTracksFromTheMoodGenres() {
        music.searchResults.put("ambient", List.of(track("n1", "z")));
        music.searchResults.put("classical", List.of(track("n2", "z")));
        music.searchResults.put("piano", List.of(track("n3", "z")));
        PlaylistEditor.EditPlan plan = editor(p -> "{\"commands\":[],\"mood\":\"calm\"}")
                .plan(playlist(track("a", "unknownArtist")), "calmer please", Map.of());
        assertTrue(plan.approximate());
        assertEquals(config.editMoodAddCount(), plan.commands().size());
        plan.commands().forEach(c -> assertInstanceOf(AddItemsCommand.class, c));
    }

    @Test
    void aMoodWithNoTracksFoundOnSpotifyAsksForSomethingMoreSpecific() {
        assertThrows(ClarificationNeededException.class, () -> editor(p -> "{\"commands\":[],\"mood\":\"calm\"}")
                .plan(playlist(track("a", "unknownArtist")), "calmer", Map.of()));
    }

    @Test
    void anUnknownMoodWordIsNotGuessedAt() {
        assertThrows(ClarificationNeededException.class, () -> editor(p -> "{\"commands\":[],\"mood\":\"zzz\"}")
                .plan(playlist(track("a", "x")), "more zzz", Map.of()));
    }

    @Test
    void whenTheModelGivesCommandsTheMoodIsIgnoredAndTheResultIsNotApproximate() {
        PlaylistEditor.EditPlan plan = editor(p -> "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}],\"mood\":\"calm\"}")
                .plan(playlist(track("a", "x")), "drop the first and calm down", Map.of());
        assertFalse(plan.approximate());
        List<PlaylistCommand> commands = plan.commands();
        assertEquals(1, commands.size());
    }

    @Test
    void aMoodEditKeepsATrackWhoseArtistGenreIsAnotherSpellingOfAMoodGenre() {
        music.searchResults.put("r&b", List.of(track("n1", "z")));
        music.searchResults.put("soul", List.of(track("n2", "z")));
        PlaylistEditor.EditPlan plan = editor(p -> "{\"commands\":[],\"mood\":\"romantic\"}").plan(
                playlist(track("a", "rockArtist"), track("b", "rnbArtist")), "make it romantic",
                Map.of("rockArtist", List.of("rock"), "rnbArtist", List.of("rnb")));
        assertEquals(1, plan.commands().size(), "the rnb track fits r&b, only the rock track is replaced");
        assertInstanceOf(ReplaceTrackCommand.class, plan.commands().get(0));
    }
}
