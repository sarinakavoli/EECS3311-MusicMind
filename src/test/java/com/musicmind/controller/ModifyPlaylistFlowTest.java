package com.musicmind.controller;

import com.musicmind.agent.ClarificationNeededException;
import com.musicmind.agent.command.FakeMusicService;
import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.Track;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.ValidationResult;
import com.musicmind.llm.LLMProvider;
import com.musicmind.agent.command.PlaylistManager;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.CandidateRepository;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TrackList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F6, F7, F10 and F12 through the controller, the command layer and the real (embedded PostgreSQL) memory layer. */
class ModifyPlaylistFlowTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    /** Spotify fake: a playlist that no longer exists answers 404, and reads or writes can be made to fail. */
    private static class Music extends FakeMusicService {
        RuntimeException failRead;
        RuntimeException failRemove;
        String failRemoveOf; // when set, only removing this uri fails
        int readLimit = 500; // like spotify.maxItems: a longer playlist is read in part and flagged truncated

        @Override public String currentUserId() { return "sp-user"; }
        @Override public List<Track> getPlaylistItems(String playlistId) {
            if (failRead != null) {
                throw failRead;
            }
            if (!playlists.containsKey(playlistId)) {
                throw new MusicServiceException("Spotify API error (HTTP 404) on GET /playlists/" + playlistId, 404);
            }
            List<Track> all = super.getPlaylistItems(playlistId);
            return all.size() > readLimit ? new TrackList(all.subList(0, readLimit), true) : all;
        }
        @Override public void removeItems(String playlistId, List<String> uris) {
            if (failRemove != null && (failRemoveOf == null || uris.contains(failRemoveOf))) {
                throw failRemove;
            }
            super.removeItems(playlistId, uris);
        }
    }

    private static Track t(String id, boolean explicit, String artist) {
        return new Track(id, "spotify:track:" + id, "Title " + id, 180_000, explicit, List.of(artist));
    }

    private static void put(Music music, String playlistId, Track... tracks) {
        music.playlists.put(playlistId, new ArrayList<>(List.of(tracks)));
    }

    private static List<String> ids(Music music, String playlistId) {
        return music.playlists.get(playlistId).stream().map(Track::id).toList();
    }

    private MemoryManager memory(Database database) {
        return new MemoryManager(database,
                new PreferenceRepository(database, () -> "sp-user"),
                new FeedbackRepository(database, () -> "sp-user"),
                new SessionRepository(database, () -> "sp-user"),
                new PlaylistHistoryRepository(database, () -> "sp-user"),
                new CandidateRepository(database, () -> "sp-user"));
    }

    private AppController controller(Music music, LLMProvider llm) {
        return controller(music, llm, memory(db));
    }

    private AppController controller(Music music, LLMProvider llm, MemoryManager memory) {
        AppController c = new AppController(music, memory, llm, config);
        c.login();
        return c;
    }

    private static LLMProvider editReply(String json) {
        return p -> p.startsWith("MUSICMIND_TASK: EDIT") ? json : "Both came from your own data.";
    }

    private MemoryManager deadMemory() {
        return memory(new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p"));
    }

    private void storeCreatedSession(String playlistId, String derived, DecisionTrace trace) {
        AgentSession s = new AgentSession("create-" + playlistId, Instant.now().minusSeconds(60), null);
        s.setStatus("done");
        s.setRequest(new AgentRequest("a mix", RequestKind.CREATE_PLAYLIST));
        s.setPlaylistId(playlistId);
        s.setDerivedData(derived);
        s.setTrace(trace);
        memory(db).saveSession(s);
    }

    // ---- F6 ----

    @Test
    void modifyRemovesTheTracksTheInstructionNamesSnapshotsFirstAndSummarisesTheChange() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", true, "x"), t("c", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"2\"}]}"));

        Playlist updated = c.modifyPlaylist("p", "remove the explicit track");

        assertEquals(List.of("a", "c"), updated.tracks().stream().map(Track::id).toList());
        assertEquals(List.of("a", "c"), ids(music, "p"));
        assertTrue(c.lastChangeSummary().contains("Title b"), c.lastChangeSummary());
        List<SnapshotInfo> snapshots = memory(db).loadSnapshotSummaries("sp-user");
        assertEquals(1, snapshots.size());
        assertEquals(3, snapshots.get(0).trackCount(), "the snapshot is the playlist before the change");
        assertTrue(c.lastModifyNotices().isEmpty(), c.lastModifyNotices().toString());
    }

    // ---- F11 during modify ----

    private static final String REMOVE_ALL_THREE = "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"},"
            + "{\"type\":\"remove\",\"track\":\"2\"},{\"type\":\"remove\",\"track\":\"3\"}]}";

    private Music threeTracks() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"));
        return music;
    }

    @Test
    void anEditThatWouldEmptyThePlaylistOffersAPartialEditAndAppliesTheChosenOne() {
        Music music = threeTracks();
        AppController c = controller(music, editReply(REMOVE_ALL_THREE));
        List<com.musicmind.domain.FeasibilityReport> asked = new ArrayList<>();
        c.setNegotiationHandler((report, options) -> {
            asked.add(report);
            return java.util.Optional.of(report.relaxations().get(0));
        });

        Playlist updated = c.modifyPlaylist("p", "shorten it by 200 minutes");

        assertEquals(1, asked.size());
        assertFalse(asked.get(0).conflicts().isEmpty());
        assertEquals(List.of("c"), ids(music, "p"), "only the first two removals were applied");
        assertEquals(List.of("c"), updated.tracks().stream().map(Track::id).toList());
        assertTrue(c.lastModifyNotices().stream().anyMatch(n -> n.contains("Adjusted at your request")),
                c.lastModifyNotices().toString());
    }

    @Test
    void cancellingTheModifyNegotiationMakesNoSpotifyWritesAndNoSnapshot() {
        Music music = threeTracks();
        AppController c = controller(music, editReply(REMOVE_ALL_THREE));
        c.setNegotiationHandler((report, options) -> java.util.Optional.empty());

        com.musicmind.agent.NegotiationCancelledException e = assertThrows(
                com.musicmind.agent.NegotiationCancelledException.class, () -> c.modifyPlaylist("p", "remove everything"));

        assertEquals(config.wording("modifyCancelled"), e.getMessage());
        assertEquals(List.of("a", "b", "c"), ids(music, "p"));
        assertTrue(music.calls.stream().noneMatch(x -> x.startsWith("remove") || x.startsWith("add") || x.startsWith("reorder")),
                music.calls.toString());
        assertTrue(memory(db).loadSnapshotSummaries("sp-user").isEmpty());
    }

    @Test
    void whenNoSmallerEditWouldWorkTheUserIsToldAndNothingIsAsked() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        boolean[] asked = {false};
        c.setNegotiationHandler((report, options) -> { asked[0] = true; return java.util.Optional.empty(); });

        com.musicmind.agent.InfeasibleRequestException e = assertThrows(
                com.musicmind.agent.InfeasibleRequestException.class, () -> c.modifyPlaylist("p", "remove it"));

        assertTrue(e.getMessage().contains(config.wording("modifyNone")), e.getMessage());
        assertFalse(asked[0]);
        assertEquals(List.of("a"), ids(music, "p"));
    }

    @Test
    void withNoHandlerTheEditStopsAndListsTheOptions() {
        Music music = threeTracks();
        AppController c = controller(music, editReply(REMOVE_ALL_THREE));

        com.musicmind.agent.InfeasibleRequestException e = assertThrows(
                com.musicmind.agent.InfeasibleRequestException.class, () -> c.modifyPlaylist("p", "remove everything"));

        assertTrue(e.getMessage().contains("Apply only the first 2 of 3 changes"), e.getMessage());
        assertEquals(List.of("a", "b", "c"), ids(music, "p"));
    }

    @Test
    void modifyRevalidatesAndReportsWhatStillBreaksTheConstraintsWithoutRollingBack() {
        Music music = new Music();
        put(music, "p", t("a", true, "x"), t("b", true, "x"), t("c", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"3\"}]}"));
        c.setPreference(c.parsePreference("explicitPolicy", "block"));

        c.modifyPlaylist("p", "drop the last one");

        assertEquals(List.of("a", "b"), ids(music, "p"));
        assertTrue(c.lastModifyNotices().stream().anyMatch(n -> n.contains("2 explicit")), c.lastModifyNotices().toString());
        assertTrue(c.lastModifyNotices().stream().anyMatch(n -> n.contains("undo")));
    }

    // ---- F6 adjustment loop (F7 drives it, then F11) ----

    private static final String ADD_ROCK = "{\"commands\":[{\"type\":\"add\",\"query\":\"rock\"}]}";
    private static final String ADD_CLEAN = "{\"commands\":[{\"type\":\"add\",\"query\":\"clean\"}]}";

    /** Answers {@code first} to the instruction as given and {@code afterHint} once the violations are stated. */
    private static LLMProvider replanning(String first, String afterHint, List<String> prompts) {
        return p -> {
            if (!p.startsWith("MUSICMIND_TASK: EDIT")) {
                return "Both came from your own data.";
            }
            prompts.add(p);
            return p.contains("explicit") && p.contains("would leave") ? afterHint : first;
        };
    }

    private Music withExplicitRockAndCleanSearch() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        music.searchResults.put("rock", List.of(t("e1", true, "y")));
        music.searchResults.put("clean", List.of(t("c1", false, "y")));
        return music;
    }

    @Test
    void anEditThatBreaksTheConstraintsIsReplannedWithTheViolationsThenApplied() {
        Music music = withExplicitRockAndCleanSearch();
        List<String> prompts = new ArrayList<>();
        AppController c = controller(music, replanning(ADD_ROCK, ADD_CLEAN, prompts));
        c.setPreference(c.parsePreference("explicitPolicy", "block"));

        c.modifyPlaylist("p", "add a rock track");

        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("e1"), "the re-plan names the offending track");
        assertEquals(List.of("a", "b", "c1"), ids(music, "p"));
        assertTrue(c.lastModifyNotices().isEmpty(), c.lastModifyNotices().toString());
    }

    @Test
    void replanningIsBoundedAndWhenNothingFitsTheEditIsRefusedWithoutWrites() {
        Music music = withExplicitRockAndCleanSearch();
        for (int i = 1; i <= 3; i++) {
            music.searchResults.put("rock" + i, List.of(t("e" + i, true, "y")));
        }
        List<String> prompts = new ArrayList<>();
        LLMProvider differentEachTime = p -> {
            if (!p.startsWith("MUSICMIND_TASK: EDIT")) {
                return "ok";
            }
            prompts.add(p);
            return "{\"commands\":[{\"type\":\"add\",\"query\":\"rock" + prompts.size() + "\"}]}";
        };
        Config cfg = config.withTunables(Map.of("plan.maxValidationRetries", "2"));
        AppController c = new AppController(music, memory(db), differentEachTime, cfg);
        c.login();
        c.setPreference(c.parsePreference("explicitPolicy", "block"));

        assertThrows(com.musicmind.agent.InfeasibleRequestException.class, () -> c.modifyPlaylist("p", "add rock"));

        assertEquals(3, prompts.size(), "one plan and two re-plans");
        assertEquals(List.of("a", "b"), ids(music, "p"));
        assertTrue(music.calls.stream().noneMatch(x -> x.startsWith("remove") || x.startsWith("add")), music.calls.toString());
    }

    @Test
    void replanningStopsWhenTheNewPlanEqualsThePreviousOne() {
        Music music = withExplicitRockAndCleanSearch();
        List<String> prompts = new ArrayList<>();
        AppController c = controller(music, replanning(ADD_ROCK, ADD_ROCK, prompts));
        c.setPreference(c.parsePreference("explicitPolicy", "block"));

        assertThrows(com.musicmind.agent.InfeasibleRequestException.class, () -> c.modifyPlaylist("p", "add rock"));

        assertEquals(2, prompts.size(), "one plan and one re-plan that changed nothing, so no third attempt");
    }

    private static final String REMOVE_A_ADD_ROCK = "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"},"
            + "{\"type\":\"add\",\"query\":\"rock\"}]}";

    @Test
    void anEditThatStillFailsAfterReplanningGoesToNegotiationAndAppliesTheChosenPart() {
        Music music = withExplicitRockAndCleanSearch();
        AppController c = controller(music, replanning(REMOVE_A_ADD_ROCK, REMOVE_A_ADD_ROCK, new ArrayList<>()));
        c.setPreference(c.parsePreference("explicitPolicy", "block"));
        List<com.musicmind.domain.FeasibilityReport> asked = new ArrayList<>();
        c.setNegotiationHandler((report, options) -> {
            asked.add(report);
            return java.util.Optional.of(report.relaxations().get(0));
        });

        c.modifyPlaylist("p", "swap a for rock");

        assertEquals(1, asked.size());
        assertTrue(asked.get(0).conflicts().get(0).contains("e1"), asked.get(0).conflicts().toString());
        assertEquals(List.of("b"), ids(music, "p"), "only the removal was applied");
    }

    @Test
    void cancellingTheNegotiationAfterReplanningMakesZeroWrites() {
        Music music = withExplicitRockAndCleanSearch();
        AppController c = controller(music, replanning(REMOVE_A_ADD_ROCK, REMOVE_A_ADD_ROCK, new ArrayList<>()));
        c.setPreference(c.parsePreference("explicitPolicy", "block"));
        c.setNegotiationHandler((report, options) -> java.util.Optional.empty());

        assertThrows(com.musicmind.agent.NegotiationCancelledException.class, () -> c.modifyPlaylist("p", "swap a for rock"));

        assertEquals(List.of("a", "b"), ids(music, "p"));
        assertTrue(music.calls.stream().noneMatch(x -> x.startsWith("remove") || x.startsWith("add") || x.startsWith("reorder")),
                music.calls.toString());
        assertTrue(memory(db).loadSnapshotSummaries("sp-user").isEmpty());
    }

    @Test
    void aViolationThePlaylistAlreadyHadDoesNotBlockAnUnrelatedEdit() {
        Music music = new Music();
        put(music, "p", t("a", true, "x"), t("b", false, "x"), t("c", false, "x"));
        List<String> prompts = new ArrayList<>();
        AppController c = controller(music, replanning("{\"commands\":[{\"type\":\"remove\",\"track\":\"3\"}]}", "", prompts));
        c.setPreference(c.parsePreference("explicitPolicy", "block"));

        c.modifyPlaylist("p", "drop the last one");

        assertEquals(1, prompts.size());
        assertEquals(List.of("a", "b"), ids(music, "p"));
    }

    @Test
    void aShorterByNEditIsNotBlockedByTheOldTargetLength() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"), t("d", false, "x"));
        storeCreatedSession("p", "{\"spec\":{\"targetMinutes\":12,\"allowExplicit\":true,\"familiarRatio\":0.5,"
                + "\"blockedArtists\":[],\"name\":\"Mix\"}}", null);
        List<String> prompts = new ArrayList<>();
        AppController c = controller(music, replanning(
                "{\"commands\":[{\"type\":\"remove\",\"track\":\"3\"},{\"type\":\"remove\",\"track\":\"4\"}],"
                        + "\"lengthChangeMinutes\":-6}", "", prompts));

        c.modifyPlaylist("p", "make it 6 minutes shorter");

        assertEquals(1, prompts.size(), "no re-plan: the old 12 minute target does not apply to this edit");
        assertEquals(List.of("a", "b"), ids(music, "p"));
        assertTrue(c.lastModifyNotices().isEmpty(), c.lastModifyNotices().toString());
    }

    @Test
    void anLlmFailureWhileReplanningFallsThroughToNegotiationWithTheCurrentPlan() {
        Music music = withExplicitRockAndCleanSearch();
        List<String> prompts = new ArrayList<>();
        LLMProvider failsOnReplan = p -> {
            if (!p.startsWith("MUSICMIND_TASK: EDIT")) {
                return "ok";
            }
            prompts.add(p);
            if (prompts.size() > 1) {
                throw new com.musicmind.llm.LLMException("rate limited");
            }
            return REMOVE_A_ADD_ROCK;
        };
        AppController c = controller(music, failsOnReplan);
        c.setPreference(c.parsePreference("explicitPolicy", "block"));
        List<com.musicmind.domain.FeasibilityReport> asked = new ArrayList<>();
        c.setNegotiationHandler((report, options) -> {
            asked.add(report);
            return java.util.Optional.of(report.relaxations().get(0));
        });

        c.modifyPlaylist("p", "swap a for rock");

        assertEquals(2, prompts.size());
        assertEquals(1, asked.size(), "negotiation ran instead of the whole edit failing");
        assertEquals(List.of("b"), ids(music, "p"));
    }

    // ---- the playlist's target length (items 2 and 3) ----

    private static final String FOUR_TRACKS_12_MIN_SPEC = "{\"spec\":{\"targetMinutes\":12,\"allowExplicit\":true,"
            + "\"familiarRatio\":0.5,\"blockedArtists\":[],\"name\":\"Mix\"}}";
    private static final String DROP_TWO_STATING = "{\"commands\":[{\"type\":\"remove\",\"track\":\"3\"},"
            + "{\"type\":\"remove\",\"track\":\"4\"}],\"lengthChangeMinutes\":-6}";
    private static final String DROP_ONE_SILENT = "{\"commands\":[{\"type\":\"remove\",\"track\":\"4\"}]}";

    private Music fourTracksTwelveMinutes() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"), t("d", false, "x"));
        storeCreatedSession("p", FOUR_TRACKS_12_MIN_SPEC, null);
        return music;
    }

    /** The duration failures validate reports now, as text. */
    private static String durationProblems(AppController c) {
        return c.validatePlaylist("p").violations().stream().filter(v -> v.contains("duration")).toList().toString();
    }

    @Test
    void aDeliberateLengthChangeMovesTheStoredTargetSoValidateDoesNotFailAgainstTheOldOne() {
        Music music = fourTracksTwelveMinutes();
        AppController c = controller(music, editReply(DROP_TWO_STATING));

        c.modifyPlaylist("p", "make it 6 minutes shorter");

        assertTrue(c.lastModifyNotices().isEmpty(), c.lastModifyNotices().toString());
        assertTrue(c.validatePlaylist("p").passed(), c.validatePlaylist("p").violations().toString());
        AppController later = controller(music, null);   // a new process reads the stored target
        assertTrue(later.validatePlaylist("p").passed());
    }

    @Test
    void anAbsoluteLengthStatedByTheEditBecomesTheTarget() {
        Music music = fourTracksTwelveMinutes();
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"4\"},"
                + "{\"type\":\"remove\",\"track\":\"3\"}],\"targetMinutes\":6}"));

        c.modifyPlaylist("p", "make it 6 minutes long");

        assertTrue(c.lastModifyNotices().isEmpty(), c.lastModifyNotices().toString());
        assertTrue(c.validatePlaylist("p").passed());
    }

    @Test
    void undoPutsTheEarlierTargetBack() {
        Music music = fourTracksTwelveMinutes();
        AppController c = controller(music, editReply(DROP_TWO_STATING));
        c.modifyPlaylist("p", "make it 6 minutes shorter");

        c.undo("p");

        music.playlists.get("p").remove(3);   // 9 minutes now: outside the original 12 minute target
        assertTrue(durationProblems(c).contains("target of 12"), durationProblems(c));
    }

    @Test
    void undoInANewRunAlsoPutsTheEarlierTargetBack() {
        Music music = fourTracksTwelveMinutes();
        controller(music, editReply(DROP_TWO_STATING)).modifyPlaylist("p", "make it 6 minutes shorter");
        AppController later = controller(music, null);

        later.undo("p");

        music.playlists.get("p").remove(3);
        assertTrue(durationProblems(later).contains("target of 12"), durationProblems(later));
    }

    @Test
    void restorePutsTheEarlierTargetBackAndUndoingTheRestoreReturnsTheNewOne() {
        Music music = fourTracksTwelveMinutes();
        AppController c = controller(music, editReply(DROP_TWO_STATING));
        c.modifyPlaylist("p", "make it 6 minutes shorter");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();

        c.restore(snapshot);
        assertEquals(4, ids(music, "p").size());
        music.playlists.get("p").remove(3);
        assertTrue(durationProblems(c).contains("target of 12"), durationProblems(c));
        music.playlists.get("p").add(t("d", false, "x"));

        c.undo("p");   // undoes the restore: two tracks and the shortened target again
        assertEquals(List.of("a", "b"), ids(music, "p"));
        assertTrue(c.validatePlaylist("p").passed(), c.validatePlaylist("p").violations().toString());
    }

    @Test
    void twoEditsAreUndoneOneTargetAtATime() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"), t("d", false, "x"), t("e", false, "x"));
        storeCreatedSession("p", FOUR_TRACKS_12_MIN_SPEC.replace("\"targetMinutes\":12", "\"targetMinutes\":15"), null);
        List<String> edits = new ArrayList<>(List.of(
                "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"},{\"type\":\"remove\",\"track\":\"2\"}],"
                        + "\"lengthChangeMinutes\":-6}",
                "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}],\"lengthChangeMinutes\":-3}"));
        AppController second = controller(music, p -> p.startsWith("MUSICMIND_TASK: EDIT") ? edits.remove(0) : "ok");
        second.modifyPlaylist("p", "6 minutes shorter");                    // 15 -> 9
        second.modifyPlaylist("p", "3 minutes shorter");                    // 9 -> 6

        second.undo("p");   // back to 9
        assertTrue(second.validatePlaylist("p").passed(), second.validatePlaylist("p").violations().toString());
        second.undo("p");   // back to 15
        music.playlists.get("p").remove(0);
        assertTrue(durationProblems(second).contains("target of 15"), durationProblems(second));
    }

    private static LLMProvider inOrder(List<String> replies) {
        List<String> left = new ArrayList<>(replies);
        return p -> p.startsWith("MUSICMIND_TASK: EDIT") ? left.remove(0) : "ok";
    }

    private static String dropTrack(String position, int minutes) {
        return "{\"commands\":[{\"type\":\"remove\",\"track\":\"" + position + "\"}],\"lengthChangeMinutes\":-" + minutes + "}";
    }

    @Test
    void restoringAnOlderSnapshotAfterTwoEditsBringsBackTheTargetOfThatTime() {
        // The live case: a snapshot saved before targets were recorded (1), then two length edits (2 and 3).
        Music music = fourTracksTwelveMinutes();
        int original = memory(db).saveSnapshot(new Playlist("p", "Mix", music.getPlaylistItems("p")));
        AppController c = controller(music, inOrder(List.of(dropTrack("4", 3), dropTrack("3", 3))));
        c.modifyPlaylist("p", "3 minutes shorter");   // 12 -> 9, saves snapshot 2
        c.modifyPlaylist("p", "3 minutes shorter");   // 9 -> 6, saves snapshot 3
        assertEquals(List.of("a", "b"), ids(music, "p"));

        c.restore(original);

        assertEquals(4, ids(music, "p").size());
        assertTrue(c.validatePlaylist("p").passed(), c.validatePlaylist("p").violations().toString());
        // Every saved version returns the target that applied when it was saved.
        int second = c.getSnapshots(c.currentUser()).stream().filter(x -> x.trackCount() == 3).findFirst().orElseThrow().id();
        c.restore(second);
        assertEquals(3, ids(music, "p").size());
        assertTrue(c.validatePlaylist("p").passed(), c.validatePlaylist("p").violations().toString());
        AppController later = controller(music, null);
        assertTrue(later.validatePlaylist("p").passed(), "a new process reads the same target");
    }

    @Test
    void undoStepsBackThroughEditsOfEveryRunWithTheMatchingTargetAndThenStops() {
        Music music = fourTracksTwelveMinutes();
        controller(music, editReply(dropTrack("4", 3))).modifyPlaylist("p", "3 minutes shorter");   // 12 -> 9, earlier process
        AppController c = controller(music, editReply(dropTrack("3", 3)));
        c.modifyPlaylist("p", "3 minutes shorter");   // 9 -> 6

        c.undo("p");   // the edit of this run: back to 9
        assertTrue(c.validatePlaylist("p").passed(), c.validatePlaylist("p").violations().toString());
        c.undo("p");   // nothing of this run is left; the earlier run's snapshot gives back the original
        assertEquals(4, ids(music, "p").size());
        assertTrue(c.validatePlaylist("p").passed(), "the earlier edit's target went back to 12: "
                + c.validatePlaylist("p").violations());
        assertThrows(PlaylistChangeException.class, () -> c.undo("p"));
        assertEquals(4, ids(music, "p").size());
    }

    @Test
    void anUndoThatSkipsAVersionAlsoTakesBackThatVersionsTarget() {
        Music music = fourTracksTwelveMinutes();
        Track third = music.playlists.get("p").get(2);
        controller(music, inOrder(List.of(dropTrack("4", 3), dropTrack("3", 3)))).modifyPlaylist("p", "3 minutes shorter");   // 12 -> 9
        controller(music, editReply(dropTrack("3", 3))).modifyPlaylist("p", "3 minutes shorter");   // 9 -> 6
        music.playlists.get("p").add(third);   // the second edit was reversed outside MusicMind

        AppController later = controller(music, null);
        later.undo("p");

        assertEquals(4, ids(music, "p").size());
        assertTrue(later.validatePlaylist("p").passed(), later.validatePlaylist("p").violations().toString());
    }

    @Test
    void aReplanThatLeavesTheLengthOutKeepsTheLengthTheFirstPlanStated() {
        Music music = withExplicitRockAndCleanSearch();   // two clean 3 minute tracks
        storeCreatedSession("p", "{\"spec\":{\"targetMinutes\":6,\"allowExplicit\":false,\"familiarRatio\":0.5,"
                + "\"blockedArtists\":[],\"name\":\"Mix\"}}", null);
        AppController c = controller(music, replanning(
                "{\"commands\":[{\"type\":\"add\",\"query\":\"rock\"}],\"lengthChangeMinutes\":3}", ADD_CLEAN, new ArrayList<>()));

        c.modifyPlaylist("p", "add a track");

        assertEquals(List.of("a", "b", "c1"), ids(music, "p"));
        assertTrue(c.lastModifyNotices().isEmpty(), c.lastModifyNotices().toString());
        assertTrue(c.validatePlaylist("p").passed(), c.validatePlaylist("p").violations().toString());
    }

    @Test
    void anEditThatDidNotAskToChangeTheLengthKeepsTheDurationNotice() {
        Music music = fourTracksTwelveMinutes();
        AppController c = controller(music, editReply(DROP_ONE_SILENT));

        c.modifyPlaylist("p", "remove the last track");

        assertTrue(c.lastModifyNotices().stream().anyMatch(n -> n.contains("target of 12")), c.lastModifyNotices().toString());
    }

    @Test
    void aStatedLengthThatTheChosenPartialEditDoesNotReachDoesNotMoveTheTarget() {
        Music music = fourTracksTwelveMinutes();
        String removeAllStating = "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"},{\"type\":\"remove\",\"track\":\"2\"},"
                + "{\"type\":\"remove\",\"track\":\"3\"},{\"type\":\"remove\",\"track\":\"4\"}],\"lengthChangeMinutes\":-12}";
        AppController c = controller(music, editReply(removeAllStating));
        c.setNegotiationHandler((report, options) -> java.util.Optional.of(report.relaxations().get(0)));

        c.modifyPlaylist("p", "remove everything");

        assertTrue(durationProblems(c).contains("target of 12"), "the target stayed: " + durationProblems(c));
    }

    @Test
    void sessionsStoredBeforeTargetsWereRecordedStillLoadAndValidate() {
        Music music = fourTracksTwelveMinutes();
        AgentSession legacy = new AgentSession("old-edit", Instant.now(), null);
        legacy.setStatus("done");
        legacy.setRequest(new AgentRequest("drop one", RequestKind.MODIFY_PLAYLIST));
        legacy.setPlaylistId("p");
        legacy.setDerivedData("{\"approximate\":false,\"mood\":\"\",\"commands\":1}");
        memory(db).saveSession(legacy);
        AppController c = controller(music, null);

        assertTrue(c.validatePlaylist("p").passed());
        assertEquals(config.wording("validateAgainstSession"), c.lastValidationNote());
        assertThrows(PlaylistChangeException.class, () -> c.undo("p"), "no snapshot exists, and the old record is not a target change");
    }

    @Test
    void theStoredTargetAndTheEditsRecordHoldNoSpotifyContent() {
        Music music = fourTracksTwelveMinutes();
        controller(music, editReply(DROP_TWO_STATING)).modifyPlaylist("p", "make it 6 minutes shorter");

        for (AgentSession s : memory(db).loadHistory("sp-user")) {
            String data = s.derivedData() == null ? "" : s.derivedData();
            assertFalse(data.contains("Title"), data);
        }
    }

    @Test
    void aMoodEditWithNoReliableSignalIsFlaggedApproximate() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "y"));
        music.searchResults.put("ambient", List.of(t("n1", false, "z")));
        music.searchResults.put("classical", List.of(t("n2", false, "z")));
        music.searchResults.put("piano", List.of(t("n3", false, "z")));
        AppController c = controller(music, editReply("{\"commands\":[],\"mood\":\"calm\"}"));

        Playlist updated = c.modifyPlaylist("p", "make it calmer");

        assertEquals(5, updated.tracks().size());
        assertTrue(c.lastModifyNotices().stream().anyMatch(n -> n.startsWith("Approximate")),
                c.lastModifyNotices().toString());
    }

    @Test
    void anAmbiguousInstructionChangesNothingAndSavesNoSnapshot() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[],\"question\":\"Which tracks?\"}"));

        ClarificationNeededException e = assertThrows(ClarificationNeededException.class,
                () -> c.modifyPlaylist("p", "fix it"));

        assertEquals("Which tracks?", e.getMessage());
        assertEquals(List.of("a"), ids(music, "p"));
        assertTrue(memory(db).loadSnapshotSummaries("sp-user").isEmpty());
        assertEquals("", c.lastChangeSummary());
    }

    @Test
    void anUnreadablePlaylistSaysWhyWithoutStatusCodesUrlsOrJson() {
        Music music = new Music();
        AppController c = controller(music, editReply("{}"));
        music.failRead = new MusicServiceException("Permission denied (HTTP 403) on GET /playlists/p/items {\"error\":{}}", 403);
        PlaylistChangeException denied = assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("p", "x"));
        assertEquals(config.wording("playlistNoPermission"), denied.getMessage());

        music.failRead = null;
        PlaylistChangeException missing = assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("nope", "x"));
        assertEquals(config.wording("playlistNotFound"), missing.getMessage());

        music.failRead = new MusicServiceException("HTTP 500 https://api.spotify.com/v1/x", 500);
        PlaylistChangeException other = assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("p", "x"));
        assertEquals(config.wording("playlistCallFailed"), other.getMessage());
        for (PlaylistChangeException e : List.of(denied, missing, other)) {
            assertFalse(e.getMessage().contains("HTTP") || e.getMessage().contains("http") || e.getMessage().contains("{"));
        }
    }

    @Test
    void aPermissionFailureWhileWritingRollsBackAndIsReportedPlainly() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        AppController c = controller(music, editReply(
                "{\"commands\":[{\"type\":\"add\",\"query\":\"q\"},{\"type\":\"remove\",\"track\":\"1\"}]}"));
        music.searchResults.put("q", List.of(t("n", false, "z")));
        music.failRemove = new MusicServiceException("Spotify API error (HTTP 403) on DELETE /playlists/p/items", 403);
        music.failRemoveOf = "spotify:track:a";

        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("p", "swap"));

        assertEquals(config.wording("playlistNoPermission"), e.getMessage());
        assertEquals(List.of("a", "b"), ids(music, "p"), "the add that had already happened was rolled back");
    }

    @Test
    void aRollbackThatFailsTheSameWayStillReportsTheOriginalProblem() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"));
        AppController c = controller(music, editReply(
                "{\"commands\":[{\"type\":\"add\",\"query\":\"q\"},{\"type\":\"remove\",\"track\":\"1\"}]}"));
        music.searchResults.put("q", List.of(t("n", false, "z")));
        music.failRemove = new MusicServiceException("Spotify API error (HTTP 403) on DELETE /playlists/p/items", 403);

        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("p", "swap"));

        assertEquals(config.wording("playlistNoPermission"), e.getMessage());
    }

    @Test
    void withTheDatabaseDownTheChangeIsRefusedWithAShortMessage() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"), deadMemory());

        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("p", "drop"));

        assertEquals(config.wording("snapshotSaveFailed"), e.getMessage());
        assertEquals(List.of("a", "b"), ids(music, "p"));
    }

    @Test
    void withoutAnLlmModifyExplainsThatItNeedsOne() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"));
        AppController c = controller(music, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.modifyPlaylist("p", "drop"));
        assertEquals(config.wording("modifyNeedsLlm"), e.getMessage());
    }

    @Test
    void blankInputIsRejectedBeforeAnythingHappens() {
        Music music = new Music();
        AppController c = controller(music, editReply("{}"));
        assertThrows(IllegalArgumentException.class, () -> c.modifyPlaylist(" ", "drop"));
        assertThrows(IllegalArgumentException.class, () -> c.modifyPlaylist("p", " "));
        assertTrue(music.calls.isEmpty());
    }

    @Test
    void modifyIsRecordedInTheHistoryButDoesNotReplaceTheCreatingSession() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        storeCreatedSession("p", "{}", null);
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));

        c.modifyPlaylist("p", "drop the first");

        List<AgentSession> history = c.getHistory(c.currentUser());
        assertTrue(history.stream().anyMatch(s -> s.request().kind() == RequestKind.MODIFY_PLAYLIST
                && "drop the first".equals(s.request().rawText()) && "p".equals(s.playlistId())));
        assertEquals("create-p", memory(db).findSessionByPlaylist("p").id());
    }

    // ---- undo ----

    @Test
    void undoRevertsTheLastChangeInThisRunAndThenInANewRunFromTheSnapshot() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"));
        AppController first = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        first.modifyPlaylist("p", "drop a");
        first.undo("p");
        assertEquals(List.of("a", "b", "c"), ids(music, "p"));

        first.modifyPlaylist("p", "drop a again");
        assertEquals(List.of("b", "c"), ids(music, "p"));
        AppController later = controller(music, editReply("{}"));   // a new process: no undo stack
        later.undo("p");
        assertEquals(List.of("a", "b", "c"), ids(music, "p"));
    }

    @Test
    void undoWithNothingToUndoOrWithTheDatabaseDownSaysSo() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"));
        PlaylistChangeException nothing = assertThrows(PlaylistChangeException.class,
                () -> controller(music, editReply("{}")).undo("p"));
        assertEquals(config.wording("undoNothing"), nothing.getMessage());

        PlaylistChangeException down = assertThrows(PlaylistChangeException.class,
                () -> controller(music, editReply("{}"), deadMemory()).undo("p"));
        assertEquals(config.wording("snapshotUnavailable"), down.getMessage());
    }

    // ---- F7 ----

    @Test
    void validateUsesTheSpecSavedWithTheCreatingSessionAndListsOffendingTracks() {
        Music music = new Music();
        put(music, "p", t("a", true, "x"), t("b", false, "bad"), t("b", false, "bad"));
        storeCreatedSession("p", "{\"spec\":{\"targetMinutes\":0,\"allowExplicit\":false,\"familiarRatio\":0.5,"
                + "\"blockedArtists\":[\"bad\"],\"name\":\"Mix\"}}", null);
        AppController c = controller(music, null);

        ValidationResult r = c.validatePlaylist("p");

        assertFalse(r.passed());
        assertEquals(List.of("a"), r.offending().get("explicit"));
        assertEquals(List.of("b"), r.offending().get("duplicates"));
        assertEquals(List.of("b", "b"), r.offending().get("blockedArtist"));
        assertEquals(config.wording("validateAgainstSession"), c.lastValidationNote());
    }

    @Test
    void validateFallsBackToStoredPreferencesAndSaysSo() {
        Music music = new Music();
        put(music, "p", t("a", true, "x"));
        AppController c = controller(music, null);
        c.setPreference(c.parsePreference("explicitPolicy", "block"));

        ValidationResult r = c.validatePlaylist("p");

        assertEquals(List.of("a"), r.offending().get("explicit"));
        assertEquals(config.wording("validateAgainstPreferences"), c.lastValidationNote());
    }

    @Test
    void validateOfAnEmptyPlaylistFailsTheMinimumLengthAndAnUnreadableOneSaysWhy() {
        Music music = new Music();
        put(music, "p");
        AppController c = controller(music, null);
        assertTrue(c.validatePlaylist("p").offending().containsKey("minLength"));
        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> c.validatePlaylist("gone"));
        assertEquals(config.wording("playlistNotFound"), e.getMessage());
    }

    // ---- F10 ----

    private static final DecisionTrace TRACE = new DecisionTrace("DiscoveryStrategy",
            Map.of("t1", "found by searching your \"jazz\" genre", "t2", "from your own listening history"),
            List.of("no duplicate tracks"));

    @Test
    void explainReadsTheStoredTraceByPlaylistIdOrSessionIdEvenInANewRun() {
        Music music = new Music();
        storeCreatedSession("p", "{}", TRACE);
        LLMProvider llm = p -> "[t1] came from your \"jazz\" genre and [t2] from your history.";
        AppController c = controller(music, llm);

        String byPlaylist = c.explain("p");
        assertEquals("one of the tracks came from your \"jazz\" genre and one of the tracks from your history.", byPlaylist);
        assertEquals(byPlaylist, c.explain("create-p"));
    }

    @Test
    void explainWithoutATraceGivesTheGeneralExplanationAndAnUnknownIdIsReported() {
        Music music = new Music();
        storeCreatedSession("p", "{}", null);
        AppController c = controller(music, null);

        assertEquals(config.wording("explainNoTrace"), c.explain("p"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.explain("zzz"));
        assertTrue(e.getMessage().contains("zzz"));
    }

    @Test
    void explainWithTheDatabaseDownSaysTheRecordIsUnavailable() {
        AppController c = controller(new Music(), null, deadMemory());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.explain("p"));
        assertEquals(config.wording("explainDbDown"), e.getMessage());
    }

    // ---- F12 ----

    @Test
    void historyListsSessionsNewestFirstAndSnapshotsAreListedWithTheirIds() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        storeCreatedSession("p", "{}", null);
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        c.modifyPlaylist("p", "drop a");

        List<AgentSession> history = c.getHistory(c.currentUser());
        assertEquals(RequestKind.MODIFY_PLAYLIST, history.get(0).request().kind());
        assertEquals(RequestKind.CREATE_PLAYLIST, history.get(1).request().kind());
        assertEquals(1, c.getSnapshots(c.currentUser()).size());
    }

    @Test
    void restoreReplaysTheSnapshotAndCanItselfBeUndone() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        c.modifyPlaylist("p", "drop a");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();
        assertEquals(List.of("b", "c"), ids(music, "p"));

        Playlist restored = c.restore(snapshot);

        assertTrue(c.lastRestoreChanged());
        assertEquals(List.of("a", "b", "c"), restored.tracks().stream().map(Track::id).toList());
        assertEquals(List.of("a", "b", "c"), ids(music, "p"));
        c.undo("p");
        assertEquals(List.of("b", "c"), ids(music, "p"));
    }

    @Test
    void restoreOfAnUnknownSnapshotOrWithTheDatabaseDownGivesAClearMessage() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"));
        PlaylistChangeException unknown = assertThrows(PlaylistChangeException.class,
                () -> controller(music, null).restore(999));
        assertEquals(String.format(config.wording("restoreUnknown"), 999), unknown.getMessage());

        PlaylistChangeException down = assertThrows(PlaylistChangeException.class,
                () -> controller(music, null, deadMemory()).restore(1));
        assertEquals(config.wording("snapshotUnavailable"), down.getMessage());
    }

    @Test
    void aPlaylistMusicMindDidNotCreateIsNamedFromSpotify() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        music.userPlaylists = List.of(new PlaylistSummary("p", "Road Trip", 2));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"2\"}]}"));

        assertEquals("Road Trip", c.getPlaylist("p").name());
        assertEquals(List.of(new PlaylistSummary("p", "Road Trip", 2)), c.getPlaylists());

        c.modifyPlaylist("p", "remove the second track");
        assertEquals("Road Trip", c.getSnapshots(c.currentUser()).get(0).name(), "the saved version keeps the real name");
    }

    @Test
    void aPlaylistThatCannotBeNamedFromSpotifyStillOpens() {
        Music music = new Music() {
            @Override public List<PlaylistSummary> getUserPlaylists() {
                throw new MusicServiceException("Spotify is down", 502);
            }
        };
        put(music, "p", t("a", false, "x"));

        assertEquals(config.wording("playlistUnnamed"), controller(music, null).getPlaylist("p").name());
    }

    private static final String SAVED_SPEC = "{\"spec\":{\"targetMinutes\":0,\"allowExplicit\":true,\"familiarRatio\":0.5,"
            + "\"blockedArtists\":[],\"name\":\"Mix\"}}";

    @Test
    void validateAppliesATrackBlockedAfterThePlaylistWasCreated() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        storeCreatedSession("p", SAVED_SPEC, null);
        AppController c = controller(music, null);
        c.setPreference(new UserPreference("blockedTracks", "b", false, 0.5));

        ValidationResult r = c.validatePlaylist("p");

        assertFalse(r.passed());
        assertEquals(List.of("b"), r.offending().get("blockedTrack"));
    }

    @Test
    void validateAppliesAnArtistBlockedAfterThePlaylistWasCreated() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "bad"));
        storeCreatedSession("p", SAVED_SPEC, null);
        AppController c = controller(music, null);
        c.setPreference(new UserPreference("blockedArtists", "bad", false, 0.5));

        assertEquals(List.of("b"), c.validatePlaylist("p").offending().get("blockedArtist"));
    }

    @Test
    void anEditCannotAddATrackBlockedAfterThePlaylistWasCreated() {
        Music music = new Music() {
            @Override public List<Track> search(String query, int limit) {
                return List.of(t("blocked", false, "x"));
            }
        };
        put(music, "p", t("a", false, "x"));
        storeCreatedSession("p", SAVED_SPEC, null);
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"add\",\"query\":\"rock\"}]}"));
        c.setPreference(new UserPreference("blockedTracks", "blocked", false, 0.5));

        assertThrows(RuntimeException.class, () -> c.modifyPlaylist("p", "add a rock track"));

        assertEquals(List.of("a"), ids(music, "p"));
    }

    @Test
    void aLaterExplicitPreferenceDoesNotRewriteThePlaylistsSavedRules() {
        Music music = new Music();
        put(music, "p", t("a", true, "x"));
        storeCreatedSession("p", SAVED_SPEC, null);
        AppController c = controller(music, null);
        c.setPreference(new UserPreference("explicitPolicy", "block", false, 0.5));

        assertTrue(c.validatePlaylist("p").passed(), "explicit content was allowed when this playlist was made");
    }

    @Test
    void anEditThatWentThroughIsRecordedEvenIfReadingItBackFails() {
        Music music = new Music() {
            @Override public void removeItems(String playlistId, List<String> uris) {
                super.removeItems(playlistId, uris);
                failRead = new MusicServiceException("Spotify is down", 502);
            }
        };
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"2\"}]}"));

        Playlist updated = c.modifyPlaylist("p", "remove the second track");

        assertEquals(List.of("a", "c"), updated.tracks().stream().map(Track::id).toList());
        assertTrue(c.lastChangeSummary().contains("Title b"), c.lastChangeSummary());
        assertTrue(c.lastModifyNotices().contains(config.wording("modifyReadBackFailed")), c.lastModifyNotices().toString());
        assertTrue(memory(db).loadHistory("sp-user").stream().anyMatch(s -> "p".equals(s.playlistId())
                && s.request().kind() == RequestKind.MODIFY_PLAYLIST), "the history holds the edit");
    }

    @Test
    void aRestoreThatWentThroughIsNotReportedAsFailedWhenReadingItBackFails() {
        Music music = new Music() {
            @Override public void addItems(String playlistId, List<String> uris) {
                super.addItems(playlistId, uris);
                failRead = new MusicServiceException("Spotify is down", 502);
            }
        };
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"2\"}]}"));
        c.modifyPlaylist("p", "remove the second track");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();

        Playlist restored = c.restore(snapshot);

        assertEquals(2, restored.tracks().size());
        assertEquals(List.of("a", "b"), ids(music, "p"));
    }

    @Test
    void aRecreatedPlaylistKeepsItsNameConstraintsAndTheTargetOfThatVersion() {
        Music music = fourTracksTwelveMinutes();
        AppController c = controller(music, editReply(dropTrack("4", 3)));
        c.modifyPlaylist("p", "3 minutes shorter");   // 12 -> 9; the snapshot holds the 12 minute version
        SnapshotInfo version = c.getSnapshots(c.currentUser()).get(0);
        music.playlists.remove("p");

        Playlist recreated = c.recreateFromSnapshot(version.id());

        assertEquals(version.name(), recreated.name());
        assertEquals(4, recreated.tracks().size());
        assertTrue(c.validatePlaylist(recreated.spotifyId()).passed(), c.validatePlaylist(recreated.spotifyId()).violations().toString());
        assertEquals(config.wording("validateAgainstSession"), c.lastValidationNote());
        AppController later = controller(music, editReply(dropTrack("4", 3)));
        later.modifyPlaylist(recreated.spotifyId(), "3 minutes shorter");
        assertEquals(version.name(), later.getSnapshots(later.currentUser()).get(0).name(), "the name survives into later versions");
    }

    @Test
    void restoreOfADeletedPlaylistOffersToRecreateItFromTheSnapshot() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        c.modifyPlaylist("p", "drop a");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();
        music.playlists.remove("p");   // the user deleted it on Spotify

        PlaylistMissingException missing = assertThrows(PlaylistMissingException.class, () -> c.restore(snapshot));
        assertEquals(snapshot, missing.snapshotId());
        assertEquals(String.format(config.wording("restoreMissing"), snapshot), missing.getMessage());

        Playlist recreated = c.recreateFromSnapshot(snapshot);

        assertNotNull(recreated.spotifyId());
        assertEquals(List.of("a", "b"), ids(music, recreated.spotifyId()));
        assertEquals(snapshotName(c, snapshot), recreated.name());
    }

    private String snapshotName(AppController c, int snapshotId) {
        return memory(db).loadSnapshot(snapshotId).name();
    }

    @Test
    void restoreOfAPlaylistThatCannotBeReadSaysSo() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        c.modifyPlaylist("p", "drop a");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();
        music.failRead = new MusicServiceException("Permission denied (HTTP 403)", 403);

        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> c.restore(snapshot));

        assertEquals(config.wording("playlistNoPermission"), e.getMessage());
    }

    // ---- review fixes ----

    private static void putMany(Music music, String playlistId, int count) {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tracks.add(t("t" + i, false, "x"));
        }
        music.playlists.put(playlistId, tracks);
    }

    @Test
    void aPlaylistOfOverOneHundredTracksIsModifiedUndoneAndRestoredCompletely() {
        Music music = new Music();
        putMany(music, "p", 150);
        List<String> original = ids(music, "p");
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"3\"}]}"));

        c.modifyPlaylist("p", "drop the third");
        assertEquals(149, ids(music, "p").size());
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();
        c.undo("p");
        assertEquals(original, ids(music, "p"));
        c.modifyPlaylist("p", "drop the third");
        c.restore(snapshot);
        assertEquals(original, ids(music, "p"));
    }

    @Test
    void aPlaylistOverTheReadLimitIsRefusedByModifyRestoreAndUndoAndNothingIsChanged() {
        Music music = new Music();
        putMany(music, "p", 600);
        List<String> original = ids(music, "p");
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        String expected = String.format(config.wording("playlistTooLong"), config.spotifyMaxItems());

        assertEquals(expected, assertThrows(PlaylistChangeException.class, () -> c.modifyPlaylist("p", "drop")).getMessage());
        // a snapshot of this playlist exists from when it was short enough to read in full
        int snapshot = memory(db).saveSnapshot(new Playlist("p", "Mix", List.of(t("t0", false, "x"))));
        assertEquals(expected, assertThrows(PlaylistChangeException.class, () -> c.undo("p")).getMessage());
        assertEquals(expected, assertThrows(PlaylistChangeException.class, () -> c.restore(snapshot)).getMessage());

        assertEquals(original, ids(music, "p"));
        assertEquals(1, memory(db).loadSnapshotSummaries("sp-user").size(), "no new snapshot was saved");
        assertTrue(music.calls.stream().noneMatch(call -> call.startsWith("remove") || call.startsWith("add")));
    }

    @Test
    void validateOfAPlaylistOverTheReadLimitSaysItCoversOnlyPartOfIt() {
        Music music = new Music();
        putMany(music, "p", 600);
        AppController c = controller(music, null);

        assertTrue(c.validatePlaylist("p").passed());
        assertTrue(c.lastValidationNote().contains(String.format(config.wording("validatePartial"), 500)),
                c.lastValidationNote());
    }

    @Test
    void aPlaylistTooLongToDescribeToTheModelIsRefusedBeforeAnyChange() {
        Music music = new Music();
        music.readLimit = 1000;
        putMany(music, "p", 250);
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.modifyPlaylist("p", "drop"));

        assertEquals(String.format(config.wording("modifyTooLong"), config.editMaxPromptTracks()), e.getMessage());
        assertEquals(250, ids(music, "p").size());
    }

    @Test
    void duplicateCopiesOfATrackSurviveModifyUndoAndRestore() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("a", false, "x"), t("c", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));

        c.modifyPlaylist("p", "drop a");
        assertEquals(List.of("b", "c"), ids(music, "p"), "every copy of the track is removed");
        c.undo("p");
        assertEquals(List.of("a", "b", "a", "c"), ids(music, "p"));

        c.modifyPlaylist("p", "drop a");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();
        c.restore(snapshot);
        assertEquals(List.of("a", "b", "a", "c"), ids(music, "p"));
    }

    @Test
    void restoringAPlaylistThatAlreadyEqualsTheSnapshotSavesNothingAndChangesNothing() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"));
        AppController c = controller(music, null);
        int snapshot = memory(db).saveSnapshot(new Playlist("p", "Mix", music.getPlaylistItems("p")));
        music.calls.clear();

        c.restore(snapshot);

        assertFalse(c.lastRestoreChanged(), "a restore that finds the playlist already matching reports no change");
        assertEquals(1, memory(db).loadSnapshotSummaries("sp-user").size());
        assertTrue(music.calls.isEmpty(), music.calls.toString());
    }

    @Test
    void aSecondRestoreOfTheSameSnapshotReportsNoChangeAndSavesNothingMore() {
        Music music = new Music();
        put(music, "p", t("a", false, "x"), t("b", false, "x"), t("c", false, "x"));
        AppController c = controller(music, editReply("{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"));
        c.modifyPlaylist("p", "drop a");
        int snapshot = c.getSnapshots(c.currentUser()).get(0).id();

        c.restore(snapshot);
        assertTrue(c.lastRestoreChanged());
        int saved = c.getSnapshots(c.currentUser()).size();
        c.restore(snapshot);

        assertFalse(c.lastRestoreChanged());
        assertEquals(saved, c.getSnapshots(c.currentUser()).size());
    }

    @Test
    void aMissingLlmKeyIsReportedBeforeAnythingIsReadFromSpotify() {
        Music music = new Music();
        music.failRead = new MusicServiceException("boom", 500);
        AppController c = controller(music, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.modifyPlaylist("p", "drop"));
        assertEquals(config.wording("modifyNeedsLlm"), e.getMessage());
    }

    @Test
    void explainReadsStoredDataWithoutSpotifyConfigured() {
        AgentSession s = new AgentSession("local-s", Instant.now(), null);
        s.setRequest(new AgentRequest("a mix", RequestKind.CREATE_PLAYLIST));
        s.setPlaylistId("p");
        s.setTrace(TRACE);
        MemoryManager local = memory(db);
        local.saveSession(s);
        AppController offline = new AppController(null, local, null, config);

        assertTrue(offline.explain("p").contains("DiscoveryStrategy"));
    }
}
