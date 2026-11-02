package com.musicmind.cli;

import com.musicmind.agent.command.FakeMusicService;
import com.musicmind.config.Config;
import com.musicmind.controller.AppController;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Track;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.MockLLMProvider;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument parsing, dispatch, output and exit status of every command, through the real controller with
 * a fake Spotify and the embedded PostgreSQL. No command needs a network or a key.
 */
class CliAppTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());
    /** A syntactically valid Spotify track id (22 letters and digits). */
    private static final String TRACK_ID = "0123456789abcdefghijAB";

    private static class Music extends FakeMusicService {
        int libraryTracks = 12;
        int novelTracks = 12;

        private Track track(String prefix, int i, String artist) {
            return new Track(prefix + i, "spotify:track:" + prefix + i, "Title " + prefix + i, 4 * 60_000, false, List.of(artist));
        }
        @Override public String currentUserId() { return "sp-user"; }
        @Override public List<Artist> getTopArtists(String r) {
            return IntStream.range(0, 6).mapToObj(i -> new Artist("a" + i, "Artist " + i, i < 2 ? List.of("rock") : List.<String>of())).toList();
        }
        @Override public List<Track> getTopItems(String type, String r) {
            return IntStream.range(0, libraryTracks).mapToObj(i -> track("t", i, "a" + i % 3)).toList();
        }
        @Override public List<Track> getSavedTracks() { return List.of(); }
        @Override public List<Track> getRecentlyPlayed() { return List.of(); }
        @Override public List<Album> getArtistAlbums(String id) { return List.of(); }
        @Override public List<Track> search(String query, int limit) {
            return IntStream.range(0, novelTracks).mapToObj(i -> track("n", i, "x" + i)).toList();
        }
    }

    private record Result(int status, String out, String err) { }

    private MemoryManager memory() {
        return new MemoryManager(db,
                new PreferenceRepository(db, () -> "sp-user"),
                new FeedbackRepository(db, () -> "sp-user"),
                new SessionRepository(db, () -> "sp-user"),
                new PlaylistHistoryRepository(db, () -> "sp-user"));
    }

    private AppController controller(Music music, LLMProvider llm) {
        return new AppController(music, memory(), llm, config);
    }

    private Result run(AppController controller, String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliApp app = new CliApp(() -> controller, new BufferedReader(new StringReader(stdin)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), config);
        int status = app.run(args);
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Result run(String... args) {
        return run(controller(new Music(), null), "", args);
    }

    private String usage(String command) {
        return config.wording("cliUsagePrefix") + " " + config.wording("cliUsage." + command);
    }

    // ---- help, dispatch, status ----

    @Test
    void helpListsEveryCommandWithItsStage1Syntax() {
        for (String help : List.of("help", "--help")) {
            Result r = run(help);

            assertEquals(CliApp.OK, r.status());
            for (String command : CliApp.COMMANDS) {
                assertTrue(r.out().contains(config.wording("cliUsage." + command)), command + " is missing from: " + r.out());
            }
            assertTrue(r.out().contains("profile [--refresh] [--range short|medium|long]"));
            assertTrue(r.out().contains("create \"<goal>\" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name \"...\"]"));
            assertTrue(r.out().contains("modify <playlistId> \"<instruction>\""));
            assertTrue(r.out().contains("feedback <trackId> accept|reject"));
        }
    }

    @Test
    void playlistsListsNameIdAndSize() {
        Music music = new Music();
        music.userPlaylists = List.of(new com.musicmind.domain.PlaylistSummary("p1", "Road Trip", 12));

        Result r = run(controller(music, null), "", "playlists");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains("Road Trip") && r.out().contains("p1") && r.out().contains("12"), r.out());
    }

    @Test
    void playlistsSaysSoWhenThereAreNone() {
        Result r = run("playlists");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains(config.wording("cliNoPlaylists")), r.out());
    }

    @Test
    void showPrintsThePlaylistsTracksAndLength() {
        Music music = new Music();
        music.seed("p1", "a", "b");

        Result r = run(controller(music, null), "", "show", "p1");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains("Title a") && r.out().contains("Title b"), r.out());
        assertTrue(r.out().contains("2 tracks") && r.out().contains("6 min"), r.out());
    }

    @Test
    void showNeedsAPlaylistId() {
        assertEquals(CliApp.USAGE, run("show").status());
    }

    @Test
    void historyListsSessionIdsSoOneCanBeOpened() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        run(c, "", "create", "a mix for working");

        Result r = run(c, "", "history");

        assertTrue(r.out().contains(c.lastSession().id()), r.out());
    }

    @Test
    void historyWithASessionIdShowsItsPlanAndResult() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        run(c, "", "create", "a mix for working");
        AgentSession created = c.lastSession();

        Result r = run(c, "", "history", created.id());

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains("a mix for working"), r.out());
        assertFalse(created.planSteps().isEmpty());
        created.planSteps().forEach(step -> assertTrue(r.out().contains(step), r.out()));
        assertTrue(r.out().contains(created.playlistId()), r.out());
        assertTrue(r.out().contains(created.status()), r.out());
        assertTrue(r.out().contains(String.format(config.wording("cliSessionTracks"), c.lastSession().context().playlist().tracks().size())), r.out());
    }

    @Test
    void historyAcceptsAPlaylistIdToo() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        run(c, "", "create", "a mix for working");

        Result r = run(c, "", "history", c.lastSession().playlistId());

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains("a mix for working"), r.out());
    }

    @Test
    void historyWithAnUnknownIdSaysSo() {
        Result r = run("history", "nope");

        assertEquals(CliApp.FAILED, r.status());
        assertTrue(r.err().contains("nope"), r.err());
    }

    @Test
    void trendsShowsEveryStepBetweenRanges() {
        Music music = new Music() {
            @Override public List<Artist> getTopArtists(String r) {
                String genre = r.startsWith("short") ? "synthwave" : r.startsWith("medium") ? "folk" : "jazz";
                return IntStream.range(0, 6).mapToObj(i -> new Artist(genre + i, "Artist " + i, List.of(genre))).toList();
            }
        };

        Result r = run(controller(music, null), "", "trends");

        assertTrue(r.out().contains(String.format(config.wording("cliTrendStep"), "long", "medium")), r.out());
        assertTrue(r.out().contains(String.format(config.wording("cliTrendStep"), "medium", "short")), r.out());
    }

    @Test
    void helpNeedsNoControllerAndNoSpotify() {
        CliApp app = new CliApp(() -> { throw new AssertionError("no controller for help"); },
                new BufferedReader(new StringReader("")), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), config);

        assertEquals(CliApp.OK, app.run(new String[] {"help"}));
    }

    @Test
    void anUnknownCommandIsAUsageErrorWithStatus2() {
        Result r = run("frobnicate");

        assertEquals(CliApp.USAGE, r.status());
        assertTrue(r.err().contains(String.format(config.wording("cliUnknownCommand"), "frobnicate")), r.err());
        assertTrue(r.out().contains("profile"), "help follows so the user sees what exists");
    }

    @Test
    void exitSaysGoodbyeAndSucceeds() {
        Result r = run("exit");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains(config.wording("cliGoodbye")));
    }

    @Test
    void withNoCommandAnInteractiveSessionRunsLinesUntilExit() {
        Result r = run(controller(new Music(), null), "help\nprefs list\nexit\nhelp\n");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains(config.wording("cliGoodbye")));
        assertTrue(r.out().contains("No preferences are set."), r.out());
        assertEquals(1, r.out().split(config.wording("cliHelpTitle"), -1).length - 2,
                "the banner help plus one typed help; the help after exit is not run");
    }

    @Test
    void anInteractiveSessionKeepsQuotedTextAsOneWordAndEndsAtEndOfInput() {
        Result r = run(controller(new Music(), null), "", "help");
        assertEquals(CliApp.OK, r.status());

        assertArrayEquals(new String[] {"create", "a calm mix", "--minutes", "30"},
                CliApp.tokenize("create \"a calm mix\" --minutes 30"));
        assertArrayEquals(new String[] {"search", "it's fine"}, CliApp.tokenize("search \"it's fine\""));
        assertArrayEquals(new String[0], CliApp.tokenize("   "));

        Result ended = run(controller(new Music(), null), "prefs list\n");
        assertEquals(CliApp.OK, ended.status(), "end of input ends the session");
    }

    // ---- login ----

    @Test
    void loginNamesTheUserAndWarnsAboutTheBrowserOnlyWhenOneWillOpen() {
        for (boolean opens : new boolean[] {true, false}) {
            AppController c = new AppController(new Music(), memory(), null, config, null, () -> opens);

            Result r = run(c, "", "login");

            assertEquals(CliApp.OK, r.status());
            assertTrue(r.out().contains(String.format(config.wording("cliLoggedIn"), "sp-user")), r.out());
            assertEquals(opens, r.out().contains(config.wording("cliBrowserLogin")), r.out());
        }
    }

    @Test
    void commandsThatNeedSpotifyExplainHowToConfigureItAndFail() {
        AppController noSpotify = new AppController(null, memory(), null, config);

        Result r = run(noSpotify, "", "trends");

        assertEquals(CliApp.FAILED, r.status());
        assertTrue(r.err().contains(config.wording("cliNotConfigured")), r.err());
    }

    // ---- F1, F2 ----

    @Test
    void profileBuildsThenShowsTheStoredOneAndRefreshRebuilds() {
        AppController c = controller(new Music(), null);

        Result built = run(c, "", "profile");
        Result stored = run(c, "", "profile");
        Result refreshed = run(c, "", "profile", "--refresh");
        Result ranged = run(c, "", "profile", "--range", "long");

        assertEquals(CliApp.OK, built.status());
        assertTrue(built.out().contains("Building your taste profile"), built.out());
        assertTrue(built.out().contains(config.spotifyAttribution()));
        assertTrue(built.out().contains(config.genreAttribution()));
        assertTrue(stored.out().contains("stored profile"), stored.out());
        assertTrue(refreshed.out().contains("Building"), refreshed.out());
        assertTrue(ranged.out().contains("Time range: long"), ranged.out());
    }

    @Test
    void profileRejectsBadArgumentsWithTheUsageLineAndStatus2() {
        for (String[] args : List.of(new String[] {"profile", "--bogus"}, new String[] {"profile", "--range"},
                new String[] {"profile", "--range", "yearly"})) {
            Result r = run(args);

            assertEquals(CliApp.USAGE, r.status(), String.join(" ", args));
            assertTrue(r.err().contains(usage("profile")), r.err());
        }
    }

    @Test
    void trendsShowsHowManyArtistsPerRangeHadGenreData() {
        Result r = run("trends");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains(String.format(config.wording("trendCoverageLine"), 2, 6)), r.out());
        assertTrue(r.out().contains(config.wording("cliNoticePrefix")), "the partial-coverage notice is shown: " + r.out());
        assertTrue(r.out().contains("partial"), r.out());
    }

    // ---- F3, F4 ----

    @Test
    void searchPrintsTheTracksAndTheLabelWhenItWasAPlainKeywordSearch() {
        Result r = run("search", "calm", "piano");

        assertEquals(CliApp.OK, r.status());
        assertTrue(r.out().contains("spotify:track:n0"), r.out());
        assertTrue(r.out().contains(config.searchFallbackLabel()), "no LLM, so the keyword search is labelled");
        assertTrue(r.out().contains(config.spotifyAttribution()));
    }

    @Test
    void searchWithoutAQueryIsAUsageError() {
        Result r = run("search");

        assertEquals(CliApp.USAGE, r.status());
        assertTrue(r.err().contains(usage("search")), r.err());
    }

    @Test
    void discoverListsRecommendationsWithReasonsAndPointsToFeedback() {
        Result r = run("discover", "--novelty", "0.5");

        assertEquals(CliApp.OK, r.status(), r.err());
        assertTrue(r.out().contains("score"), r.out());
        assertTrue(r.out().contains(usage("feedback")), r.out());
    }

    @Test
    void discoverRejectsANonNumberAndAnOutOfRangeNovelty() {
        Result notNumber = run("discover", "--novelty", "lots");
        Result outOfRange = run("discover", "--novelty", "1.5");
        Result unknown = run("discover", "--wild");

        assertEquals(CliApp.USAGE, notNumber.status());
        assertTrue(notNumber.err().contains(String.format(config.wording("cliBadNumber"), "--novelty", "lots")), notNumber.err());
        assertEquals(CliApp.USAGE, outOfRange.status());
        assertTrue(outOfRange.err().contains("between 0 and 1"), outOfRange.err());
        assertEquals(CliApp.USAGE, unknown.status());
    }

    // ---- F5, F11 ----

    @Test
    void createMakesThePlaylistAndPrintsTracksExplanationAndAttribution() {
        Music music = new Music();
        Result r = run(controller(music, new MockLLMProvider()), "", "create", "a mix for working", "--no-explicit",
                "--name", "Work Mix");

        assertEquals(CliApp.OK, r.status(), r.err());
        assertTrue(r.out().contains("Created \"MusicMind Mix\"") || r.out().contains("Created \"Work Mix\""), r.out());
        assertTrue(r.out().contains("planning..."), "progress is shown: " + r.out());
        assertTrue(r.out().contains(config.spotifyAttribution()));
        assertTrue(music.calls.stream().anyMatch(c -> c.startsWith("create sp-user")), music.calls.toString());
    }

    @Test
    void createWithoutTheAiModelFailsWithTheNeedsLlmMessageAndNothingIsCreated() {
        Music music = new Music();
        Result r = run(controller(music, null), "", "create", "a calm mix");

        assertEquals(CliApp.FAILED, r.status());
        assertTrue(r.err().contains(config.wording("planNeedsLlm")), r.err());
        assertTrue(music.calls.isEmpty(), music.calls.toString());
    }

    @Test
    void createArgumentErrorsAreUsageErrors() {
        for (String[] args : List.of(new String[] {"create"}, new String[] {"create", "a", "b"},
                new String[] {"create", "x", "--minutes", "abc"}, new String[] {"create", "x", "--minutes"},
                new String[] {"create", "x", "--familiar", "lots"}, new String[] {"create", "x", "--wat"})) {
            Result r = run(args);

            assertEquals(CliApp.USAGE, r.status(), String.join(" ", args));
            assertTrue(r.err().contains(usage("create")), r.err());
        }
    }

    @Test
    void anImpossibleRequestAsksWhatToRelaxAndTheNumberTypedIsApplied() {
        Music music = new Music();
        music.libraryTracks = 5;
        music.novelTracks = 5;
        LLMProvider ninety = new MockLLMProvider() {
            @Override public String complete(String prompt) {
                return prompt.startsWith("MUSICMIND_TASK: PLAN")
                        ? "{\"targetMinutes\":90,\"allowExplicit\":true,\"familiarRatio\":0.7,\"name\":\"Long\",\"confidence\":0.9,\"mood\":\"\"}"
                        : super.complete(prompt);
            }
        };

        Result r = run(controller(music, ninety), "1\n", "create", "a long one");

        assertEquals(CliApp.OK, r.status(), r.err() + r.out());
        assertTrue(r.out().contains(config.wording("cliProblemPrefix")), r.out());
        assertTrue(r.out().contains("1) "), r.out());
        assertTrue(r.out().contains("Created \"Long\""), r.out());
    }

    @Test
    void decliningTheProposedChangeCancelsWithNonZeroStatusAndCreatesNothing() {
        Music music = new Music();
        music.libraryTracks = 5;
        music.novelTracks = 5;
        LLMProvider ninety = new MockLLMProvider() {
            @Override public String complete(String prompt) {
                return prompt.startsWith("MUSICMIND_TASK: PLAN")
                        ? "{\"targetMinutes\":90,\"allowExplicit\":true,\"familiarRatio\":0.7,\"name\":\"Long\",\"confidence\":0.9,\"mood\":\"\"}"
                        : super.complete(prompt);
            }
        };

        Result r = run(controller(music, ninety), "no\n", "create", "a long one");

        assertEquals(CliApp.FAILED, r.status());
        assertTrue(r.err().contains(config.wording("negotiationCancelled")), r.err());
        assertTrue(music.calls.stream().noneMatch(c -> c.startsWith("create")), music.calls.toString());
    }

    // ---- F6, F7, F10, F12 on a created playlist ----

    @Test
    void modifyUndoValidateExplainHistoryAndRestoreWorkOnACreatedPlaylist() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        assertEquals(CliApp.OK, run(c, "", "create", "a mix for working").status());
        String id = music.playlists.keySet().iterator().next();
        int before = music.playlists.get(id).size();

        Result modify = run(c, "", "modify", id, "remove the first track");
        assertEquals(CliApp.OK, modify.status(), modify.err());
        assertTrue(modify.out().contains(config.wording("cliChangedPrefix")), modify.out());
        assertEquals(before - 1, music.playlists.get(id).size());

        Result undo = run(c, "", "undo", id);
        assertEquals(CliApp.OK, undo.status(), undo.err());
        assertEquals(before, music.playlists.get(id).size());

        Result validate = run(c, "", "validate", id);
        assertTrue(validate.out().contains(config.wording("cliPass")) || validate.out().contains(config.wording("cliFail")),
                validate.out());
        assertEquals(validate.out().contains(config.wording("cliPass")) ? CliApp.OK : CliApp.FAILED, validate.status());

        Result explain = run(c, "", "explain", id);
        assertEquals(CliApp.OK, explain.status(), explain.err());
        assertFalse(explain.out().isBlank());

        Result history = run(c, "", "history");
        assertEquals(CliApp.OK, history.status());
        assertTrue(history.out().contains("CREATE_PLAYLIST") && history.out().contains("MODIFY_PLAYLIST"), history.out());
        String snapshotId = c.getSnapshots(c.currentUser()).get(0).id() + "";
        assertTrue(history.out().contains("  " + snapshotId + "  "), history.out());

        // The undo already put the playlist back to that saved version, so restoring it changes nothing.
        Result restore = run(c, "", "restore", snapshotId);
        assertEquals(CliApp.OK, restore.status(), restore.err());
        assertTrue(restore.out().contains("already matches that saved version"), restore.out());
        assertFalse(restore.out().contains("Undo with"), restore.out());
    }

    @Test
    void restoreSaysWhatChangedOnlyTheFirstTimeAndOffersUndoOnlyThen() {
        Music music = new Music();
        music.seed("p", "a", "b", "c");
        AppController c = controller(music, REMOVE_ALL_BUT_ONE);
        assertEquals(CliApp.OK, run(c, "", "modify", "p", "drop the first").status());
        String snapshotId = c.getSnapshots(c.currentUser()).get(0).id() + "";

        Result first = run(c, "", "restore", snapshotId);
        Result second = run(c, "", "restore", snapshotId);

        assertEquals(CliApp.OK, first.status(), first.err());
        assertTrue(first.out().contains("Restored") && first.out().contains("Undo with: undo p"), first.out());
        assertEquals(CliApp.OK, second.status(), second.err());
        assertFalse(second.out().contains("Restored") || second.out().contains("Undo with"), second.out());
        assertTrue(second.out().contains("already"), second.out());
    }

    private static final LLMProvider REMOVE_ALL_BUT_ONE = prompt -> prompt.startsWith("MUSICMIND_TASK: EDIT")
            ? "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}"
            : "ok";

    private static final LLMProvider REMOVE_ALL = prompt -> prompt.startsWith("MUSICMIND_TASK: EDIT")
            ? "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"},{\"type\":\"remove\",\"track\":\"2\"},"
                    + "{\"type\":\"remove\",\"track\":\"3\"}]}"
            : "ok";

    @Test
    void modifyThatCannotBeAppliedAsAskedPromptsAndAppliesTheNumberTyped() {
        Music music = new Music();
        music.seed("p", "a", "b", "c");

        Result r = run(controller(music, REMOVE_ALL), "1\n", "modify", "p", "shorten it by 200 minutes");

        assertEquals(CliApp.OK, r.status(), r.err() + r.out());
        assertTrue(r.out().contains(config.wording("cliProblemPrefix")), r.out());
        assertTrue(r.out().contains("1) Apply only the first 2 of 3 changes"), r.out());
        assertEquals(List.of("c"), music.ids("p"));
    }

    @Test
    void decliningTheModifyPromptChangesNothingAndFails() {
        Music music = new Music();
        music.seed("p", "a", "b", "c");

        Result r = run(controller(music, REMOVE_ALL), "no\n", "modify", "p", "remove everything");

        assertEquals(CliApp.FAILED, r.status());
        assertTrue(r.err().contains(config.wording("modifyCancelled")), r.err());
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void failuresOnUnknownPlaylistsOrSnapshotsGiveNonZeroStatusAndAMessage() {
        AppController c = controller(new Music(), new MockLLMProvider());

        for (String[] args : List.of(new String[] {"validate", "nope"}, new String[] {"explain", "nope"},
                new String[] {"undo", "nope"}, new String[] {"modify", "nope", "remove the first track"},
                new String[] {"restore", "999"})) {
            Result r = run(c, "", args);

            assertEquals(CliApp.FAILED, r.status(), String.join(" ", args) + " -> " + r.out() + r.err());
            assertFalse(r.err().isBlank(), String.join(" ", args));
        }
    }

    @Test
    void wrongArgumentCountsAreUsageErrorsForTheIdCommands() {
        for (String[] args : List.of(new String[] {"modify", "p"}, new String[] {"modify"}, new String[] {"undo"},
                new String[] {"validate", "a", "b"}, new String[] {"explain"}, new String[] {"restore"},
                new String[] {"restore", "abc"}, new String[] {"restore", "-1"})) {
            Result r = run(args);

            assertEquals(CliApp.USAGE, r.status(), String.join(" ", args));
            assertTrue(r.err().contains(config.wording("cliUsagePrefix")), r.err());
        }
    }

    // ---- F8 ----

    @Test
    void prefsSetGetAndListRoundTrip() {
        AppController c = controller(new Music(), null);

        Result set = run(c, "", "prefs", "set", "explicitPolicy", "block");
        Result get = run(c, "", "prefs", "get", "explicitPolicy");
        Result list = run(c, "", "prefs", "list");
        Result missing = run(c, "", "prefs", "get", "familiarBias");

        assertEquals(CliApp.OK, set.status(), set.err());
        assertTrue(set.out().contains("Saved explicitPolicy"), set.out());
        assertTrue(get.out().contains("explicitPolicy = "), get.out());
        assertTrue(list.out().contains("explicitPolicy"), list.out());
        assertTrue(missing.out().contains("No preference 'familiarBias' is set."), missing.out());
    }

    @Test
    void prefsRejectsUnknownKeysBadValuesAndBadSyntaxWithStatus2() {
        for (String[] args : List.of(new String[] {"prefs"}, new String[] {"prefs", "set", "explicitPolicy"},
                new String[] {"prefs", "get"}, new String[] {"prefs", "set", "colour", "red"},
                new String[] {"prefs", "set", "familiarBias", "9"})) {
            Result r = run(args);

            assertEquals(CliApp.USAGE, r.status(), String.join(" ", args));
            assertFalse(r.err().isBlank());
        }
    }

    // ---- F9 ----

    @Test
    void feedbackSavesAValidIdOrUriAndNothingElse() {
        AppController c = controller(new Music(), null);
        MemoryManager memory = c.memory();

        Result byId = run(c, "", "feedback", TRACK_ID, "accept");
        Result byUri = run(c, "", "feedback", "spotify:track:" + TRACK_ID, "reject");

        assertEquals(CliApp.OK, byId.status(), byId.err());
        assertEquals(CliApp.OK, byUri.status(), byUri.err());
        assertEquals(1, memory.loadFeedback("sp-user").size(), "the same track: the later verdict replaced the earlier one");
        assertEquals(TRACK_ID, memory.loadFeedback("sp-user").get(0).trackId());
    }

    @Test
    void feedbackWithAnIdThatIsNotATrackIdSavesNothingAndShowsTheUsage() {
        AppController c = controller(new Music(), null);

        for (String bad : List.of("TRACK_ID", "t1", "spotify:album:" + TRACK_ID, TRACK_ID + "x")) {
            Result r = run(c, "", "feedback", bad, "accept");

            assertEquals(CliApp.USAGE, r.status(), bad);
            assertTrue(r.err().contains(config.wording("feedbackBadTrackId")), r.err());
            assertTrue(r.err().contains(usage("feedback")), r.err());
        }
        assertTrue(c.memory().loadFeedback("sp-user").isEmpty(), "nothing was saved");
        assertEquals(CliApp.USAGE, run(c, "", "feedback", TRACK_ID, "maybe").status());
        assertEquals(CliApp.USAGE, run(c, "", "feedback", TRACK_ID).status());
        assertTrue(c.memory().loadFeedback("sp-user").isEmpty());
    }
}
