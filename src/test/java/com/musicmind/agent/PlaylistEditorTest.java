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
}
