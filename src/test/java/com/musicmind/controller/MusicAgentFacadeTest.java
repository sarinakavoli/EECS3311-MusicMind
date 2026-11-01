package com.musicmind.controller;

import com.musicmind.agent.Planner;
import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.ResponseParser;
import com.musicmind.agent.SearchRanker;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.TrendComparator;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.MockLLMProvider;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import com.musicmind.tool.MemoryTool;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicAgentFacadeTest extends PostgresTestBase {

    private static final User USER = new User("user-1", "User One", "user-1");

    private final Config config = Config.of(Map.of());

    /** Fake Spotify: ranges map to artist lists; records the order of calls. */
    private static class FakeMusic extends StubMusicService {
        final List<String> calls = new ArrayList<>();
        final Map<String, List<Artist>> artistsByRange = new LinkedHashMap<>();
        List<Track> tracks = tracks(8);
        List<Track> saved = tracks(3);
        RuntimeException savedFailure;
        RuntimeException artistsFailure;
        RuntimeException tracksFailure;
        List<Track> searchResults = List.of();
        RuntimeException searchFailure;
        final List<String> searches = new ArrayList<>();
        int searchLimit;

        @Override public List<Track> search(String query, int limit) {
            searches.add(query);
            searchLimit = limit;
            if (searchFailure != null) throw searchFailure;
            return searchResults;
        }

        FakeMusic() {
            for (String r : List.of("short", "medium", "long")) {
                artistsByRange.put(r, artists(8, "pop"));
            }
        }

        @Override public List<Artist> getTopArtists(String range) {
            calls.add("getTopArtists:" + range);
            if (artistsFailure != null) throw artistsFailure;
            return artistsByRange.getOrDefault(range, List.of());
        }

        @Override public List<Track> getTopItems(String type, String range) {
            calls.add("getTopItems:" + type + ":" + range);
            if (tracksFailure != null) throw tracksFailure;
            return tracks;
        }

        @Override public List<Track> getSavedTracks() {
            calls.add("getSavedTracks");
            if (savedFailure != null) throw savedFailure;
            return saved;
        }
    }

    static List<Artist> artists(int n, String... genres) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Artist("a" + i + String.join("", genres), "Secret Artist " + i, List.of(genres)))
                .toList();
    }

    static List<Track> tracks(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Track("t" + i, "spotify:track:t" + i, "Secret Title " + i, 1000, false, List.of()))
                .toList();
    }

    private MemoryManager memory() {
        return new MemoryManager(db,
                new PreferenceRepository(db, currentUser::get),
                new FeedbackRepository(db, currentUser::get),
                new SessionRepository(db, currentUser::get),
                new PlaylistHistoryRepository(db, currentUser::get));
    }

    private MusicAgentFacade facade(FakeMusic music, LLMProvider llm, MemoryManager memory, ToolManager tools) {
        ProfileBuilder builder = new ProfileBuilder(config);
        PromptBuilder prompts = new PromptBuilder(config);
        return new MusicAgentFacade(tools, memory, builder, new TrendComparator(builder, config),
                prompts, llm, config, new Planner(llm, prompts, new ResponseParser(config), config),
                new ResponseParser(config), new SearchRanker(config),
                new com.musicmind.agent.RecommendationEngine(tools, config), currentUser::get);
    }

    private ToolManager tools(FakeMusic music, MemoryManager memory, List<String> toolLog) {
        ToolManager tm = new ToolManager() {
            @Override public ToolResult execute(String name, Map<String, Object> args) {
                toolLog.add(name + (args.containsKey("type") ? ":" + args.get("type") : ""));
                return super.execute(name, args);
            }
        };
        SpotifyTool.allFor(music).forEach(tm::register);
        tm.register(new MemoryTool(memory));
        return tm;
    }

    private MusicAgentFacade facade(FakeMusic music, LLMProvider llm) {
        MemoryManager memory = memory();
        return facade(music, llm, memory, tools(music, memory, new ArrayList<>()));
    }

    @Test
    void analyzeTasteFollowsTheSd03InteractionOrder() {
        FakeMusic music = new FakeMusic();
        List<String> toolLog = new ArrayList<>();
        List<String> order = new ArrayList<>();
        MemoryManager memory = memory();
        LLMProvider llm = prompt -> {
            order.add("complete:" + PromptBuilder.kindOf(prompt).orElseThrow());
            return "You love pop.";
        };
        ToolManager tm = tools(music, memory, toolLog);

        ListeningProfile profile = facade(music, llm, memory, tm).analyzeTaste(USER);

        assertEquals(List.of("getTopItems:artists", "getTopItems:tracks", "getSavedTracks"), toolLog);
        assertEquals(List.of("getTopArtists:medium", "getTopItems:tracks:medium", "getSavedTracks"), music.calls);
        assertEquals(List.of("complete:SUMMARY"), order);
        assertEquals("You love pop.", profile.summary());
        assertEquals(1, memory.loadHistory("user-1").size(), "saveSession is the last step");
    }

    @Test
    void theRangeIsPassedToBothTopItemTools() {
        FakeMusic music = new FakeMusic();
        facade(music, new MockLLMProvider()).analyzeTaste(USER, "long");
        assertTrue(music.calls.contains("getTopArtists:long"));
        assertTrue(music.calls.contains("getTopItems:tracks:long"));
    }

    @Test
    void anUnknownRangeIsRejectedBeforeAnyCall() {
        FakeMusic music = new FakeMusic();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> facade(music, null).analyzeTaste(USER, "forever"));
        assertTrue(e.getMessage().contains("short"), e.getMessage());
        assertTrue(music.calls.isEmpty());
    }

    @Test
    void littleHistoryGivesAPartialProfileWithANoticeAndStillSavesIt() {
        FakeMusic music = new FakeMusic();
        music.artistsByRange.put("medium", artists(2, "pop"));
        music.tracks = tracks(1);
        music.saved = List.of();
        MemoryManager memory = memory();

        ListeningProfile profile = facade(music, new MockLLMProvider(), memory,
                tools(music, memory, new ArrayList<>())).analyzeTaste(USER);

        assertTrue(profile.isPartial());
        assertFalse(profile.notices().isEmpty());
        assertFalse(profile.genreHistogram().isEmpty());
        AgentSession stored = memory.loadHistory("user-1").get(0);
        assertTrue(stored.derivedData().contains("\"partial\":true"), stored.derivedData());
    }

    @Test
    void aFailedSavedTracksReadLeavesAPartialProfileNotAFailure() {
        FakeMusic music = new FakeMusic();
        music.savedFailure = new MusicServiceException("Spotify returned 500");

        ListeningProfile profile = facade(music, null).analyzeTaste(USER);

        assertTrue(profile.isPartial());
        assertTrue(profile.notices().contains(config.wording("readFailedSavedTracks")), profile.notices().toString());
        assertEquals("pop", profile.topGenre());
    }

    @Test
    void aCutShortSavedTracksListGivesAProfileNoticeThatIsStoredToo() {
        FakeMusic music = new FakeMusic();
        music.saved = new com.musicmind.music.TrackList(tracks(3), true);
        MemoryManager memory = memory();

        ListeningProfile profile = facade(music, null, memory, tools(music, memory, new ArrayList<>())).analyzeTaste(USER);

        String notice = String.format(config.wording("savedTracksCapped"), config.spotifyMaxSavedTracks());
        assertTrue(profile.notices().contains(notice), profile.notices().toString());
        assertTrue(memory.loadHistory("user-1").get(0).derivedData().contains("were not checked"));
    }

    @Test
    void aCompleteSavedTracksListAddsNoNotice() {
        FakeMusic music = new FakeMusic();
        music.saved = new com.musicmind.music.TrackList(tracks(3), false);

        ListeningProfile profile = facade(music, null).analyzeTaste(USER);

        assertTrue(profile.notices().isEmpty(), profile.notices().toString());
    }

    @Test
    void whenEveryReadFailsTheCallFailsWithTheReasons() {
        FakeMusic music = new FakeMusic();
        music.artistsFailure = new MusicServiceException("down A");
        music.tracksFailure = new MusicServiceException("down T");
        music.savedFailure = new MusicServiceException("down S");

        MusicServiceException e = assertThrows(MusicServiceException.class, () -> facade(music, null).analyzeTaste(USER));
        assertEquals(config.wording("profileAllReadsFailed"), e.getMessage());
        assertSame(music.artistsFailure, e.getCause(), "the original failure stays available as the cause");
    }

    private static final String RAW = "Spotify API error (HTTP 503) on GET https://api.spotify.com/v1/me/top/artists?limit=20"
            + ": {\"error\":{\"status\":503,\"message\":\"upstream connect error\"}}";

    private static final List<String> RAW_DETAILS = List.of("HTTP", "http", "api.spotify", "upstream", "/v1", "GET");

    /** No status, URL or response text; for stored JSON (which has braces of its own) only the details are checked. */
    private static void assertNoRawDetail(String text) {
        for (String raw : RAW_DETAILS) {
            assertFalse(text.contains(raw), "found '" + raw + "' in: " + text);
        }
    }

    private static void assertNothingRaw(String text) {
        assertNoRawDetail(text);
        assertFalse(text.contains("503"), "status in: " + text); // not checked in stored JSON: a timestamp can hold these digits
        assertFalse(text.contains("{") || text.contains("}"), "JSON in: " + text);
    }

    @Test
    void aFailedReadShowsOnlyShortWordingInTheProfileTheStoredDataAndThePrompt() {
        FakeMusic music = new FakeMusic();
        music.savedFailure = new MusicServiceException(RAW, 503);
        music.tracksFailure = new MusicServiceException(RAW, 503);
        List<String> prompts = new ArrayList<>();
        MemoryManager memory = memory();

        ListeningProfile profile = facade(music, p -> { prompts.add(p); return "ok"; }, memory,
                tools(music, memory, new ArrayList<>())).analyzeTaste(USER);

        assertTrue(profile.notices().contains(config.wording("readFailedTopTracks")), profile.notices().toString());
        profile.notices().forEach(MusicAgentFacadeTest::assertNothingRaw);
        assertNoRawDetail(memory.loadHistory("user-1").get(0).derivedData());
        prompts.forEach(MusicAgentFacadeTest::assertNothingRaw);
        assertNothingRaw(profile.summary());
    }

    @Test
    void anExpiredTokenPropagatesSoTheUserCanLogInAgain() {
        FakeMusic music = new FakeMusic();
        music.artistsFailure = new AuthExpiredException("expired");
        assertThrows(AuthExpiredException.class, () -> facade(music, null).analyzeTaste(USER));
    }

    @Test
    void onlyDerivedDataIsPersistedNeverSpotifyContent() {
        FakeMusic music = new FakeMusic();
        MemoryManager memory = memory();

        facade(music, p -> "You love pop.", memory, tools(music, memory, new ArrayList<>())).analyzeTaste(USER);

        AgentSession stored = memory.loadHistory("user-1").get(0);
        assertEquals(RequestKind.ANALYZE_TASTE, stored.request().kind());
        String json = stored.derivedData();
        assertFalse(json.contains("Secret"), "no titles or artist names: " + json);
        assertTrue(json.contains("genreHistogram") && json.contains("artistIds") && json.contains("trackIds")
                && json.contains("savedTrackIds") && json.contains("summary") && json.contains("generatedAt"), json);
    }

    @Test
    void theSummaryPromptCarriesNoNamesOrTitles() {
        FakeMusic music = new FakeMusic();
        List<String> prompts = new ArrayList<>();
        facade(music, p -> { prompts.add(p); return "ok"; }).analyzeTaste(USER);
        assertFalse(prompts.get(0).contains("Secret"), prompts.get(0));
    }

    @Test
    void withNoLlmTheSummaryIsComputedFromGenresAndLabelled() {
        ListeningProfile p = facade(new FakeMusic(), null).analyzeTaste(USER);
        assertTrue(p.summary().contains("pop"), p.summary());
        assertTrue(p.summary().endsWith(config.computedLabel()), p.summary());
        assertFalse(p.summary().contains("Secret"));
    }

    @Test
    void anLlmFailureOrUnusableReplyFallsBackWithTheLabel() {
        for (LLMProvider bad : List.<LLMProvider>of(
                prompt -> { throw new LLMException("boom"); }, prompt -> "{}", prompt -> "  ")) {
            ListeningProfile p = facade(new FakeMusic(), bad).analyzeTaste(USER);
            assertTrue(p.summary().endsWith(config.computedLabel()), p.summary());
        }
    }

    @Test
    void anUncheckedLlmFailureStillFallsBackAndSavesTheSession() {
        MemoryManager memory = memory();
        FakeMusic music = new FakeMusic();
        ListeningProfile p = facade(music, prompt -> { throw new IllegalStateException("parser bug"); }, memory,
                tools(music, memory, new ArrayList<>())).analyzeTaste(USER);
        assertTrue(p.summary().endsWith(config.computedLabel()), p.summary());
        assertEquals(1, memory.loadHistory("user-1").size());
    }

    @Test
    void anUncheckedLlmFailureStillGivesALabelledTrendNarrative() {
        TrendReport r = facade(trendMusic(), p -> { throw new IllegalStateException("parser bug"); }).analyzeTrends(USER);
        assertTrue(r.narrative().endsWith(config.computedLabel()), r.narrative());
    }

    @Test
    void lastProfileKnowsWhichRangeItCovers() {
        FakeMusic music = new FakeMusic();
        MemoryManager memory = memory();
        MusicAgentFacade facade = facade(music, null, memory, tools(music, memory, new ArrayList<>()));
        assertEquals("long", facade.analyzeTaste(USER, "long").range());
        assertEquals("long", facade.lastProfile(USER).orElseThrow().range());
        facade.analyzeTaste(USER);
        assertEquals("medium", facade.lastProfile(USER).orElseThrow().range());
    }

    @Test
    void aMalformedListInStoredJsonMeansNoProfileInsteadOfACrash() {
        for (String broken : List.of("\"artistIds\":[{\"x\":1}]", "\"trackIds\":[null]",
                "\"artistGenres\":{\"a\":[{\"g\":1}]}")) {
            MemoryManager memory = memory();
            AgentSession s = new AgentSession(java.util.UUID.randomUUID().toString(), java.time.Instant.now(), null);
            s.setStatus("done");
            s.setRequest(new com.musicmind.domain.AgentRequest("view taste profile", RequestKind.ANALYZE_TASTE));
            s.setDerivedData("{\"genreHistogram\":{\"pop\":1.0}," + broken + "}");
            memory.saveSession(s);
            FakeMusic music = new FakeMusic();

            assertTrue(facade(music, null, memory, tools(music, memory, new ArrayList<>()))
                    .lastProfile(USER).isEmpty(), broken);
        }
    }

    @Test
    void anLlmSentenceIsKeptWithoutTheComputedLabel() {
        ListeningProfile p = facade(new FakeMusic(), p2 -> "\"You love pop.\"\nextra").analyzeTaste(USER);
        assertEquals("You love pop.", p.summary());
    }

    @Test
    void anEmptyAccountGetsAnHonestSummaryAndNoLlmCall() {
        FakeMusic music = new FakeMusic();
        music.artistsByRange.put("medium", List.of());
        music.tracks = List.of();
        music.saved = List.of();
        List<String> prompts = new ArrayList<>();

        ListeningProfile p = facade(music, x -> { prompts.add(x); return "x"; }).analyzeTaste(USER);

        assertTrue(p.isEmpty() && p.isPartial());
        assertTrue(prompts.isEmpty());
        assertTrue(p.summary().toLowerCase().contains("not enough"), p.summary());
    }

    @Test
    void lastProfileReturnsTheStoredDerivedProfileOrNothing() {
        FakeMusic music = new FakeMusic();
        MemoryManager memory = memory();
        MusicAgentFacade facade = facade(music, p -> "You love pop.", memory, tools(music, memory, new ArrayList<>()));
        assertTrue(facade.lastProfile(USER).isEmpty());

        ListeningProfile built = facade.analyzeTaste(USER);
        ListeningProfile last = facade.lastProfile(USER).orElseThrow();

        assertEquals(built.genreHistogram(), last.genreHistogram());
        assertEquals("You love pop.", last.summary());
        assertEquals(built.generatedAt(), last.generatedAt());
        assertEquals(built.topArtists().stream().map(Artist::id).toList(),
                last.topArtists().stream().map(Artist::id).toList());
        assertEquals(built.savedTrackIds(), last.savedTrackIds());
        assertEquals(built.isPartial(), last.isPartial());
        assertTrue(last.topArtists().stream().allMatch(a -> a.name().isBlank()), "ids only, no names");
        assertEquals(1, memory.loadHistory("user-1").size(), "lastProfile must not write");
    }

    @Test
    void lastProfileIgnoresOtherUsersAndOtherSessionKinds() {
        FakeMusic music = new FakeMusic();
        MemoryManager memory = memory();
        MusicAgentFacade facade = facade(music, null, memory, tools(music, memory, new ArrayList<>()));
        facade.analyzeTaste(USER);
        assertTrue(facade.lastProfile(new User("someone-else", "x", "someone-else")).isEmpty());
    }

    // ---- F2 ----

    private FakeMusic trendMusic() {
        FakeMusic music = new FakeMusic();
        music.artistsByRange.put("short", artists(8, "synthwave"));
        music.artistsByRange.put("medium", artists(8, "pop"));
        music.artistsByRange.put("long", artists(8, "jazz"));
        return music;
    }

    @Test
    void analyzeTrendsFetchesAllThreeRangesAndComparesThem() {
        FakeMusic music = trendMusic();
        List<String> toolLog = new ArrayList<>();
        MemoryManager memory = memory();
        List<String> prompts = new ArrayList<>();
        MusicAgentFacade facade = facade(music, p -> { prompts.add(p); return "Your taste moved."; }, memory,
                tools(music, memory, toolLog));

        TrendReport r = facade.analyzeTrends(USER);

        assertEquals(List.of("getTopItems:artists", "getTopItems:artists", "getTopItems:artists"), toolLog);
        assertEquals(List.of("getTopArtists:short", "getTopArtists:medium", "getTopArtists:long"), music.calls);
        assertEquals(List.of("synthwave"), r.addedGenres());
        assertEquals(List.of("jazz"), r.droppedGenres());
        assertEquals("Your taste moved.", r.narrative());
        assertEquals(1, prompts.size());
        assertEquals(PromptBuilder.Kind.TREND, PromptBuilder.kindOf(prompts.get(0)).orElseThrow());
        assertFalse(prompts.get(0).contains("Secret"));
        assertTrue(memory.loadHistory("user-1").isEmpty(), "trends are display-only");
    }

    @Test
    void trendsWithNoLlmUseALabelledNarrativeComputedFromTheDiff() {
        TrendReport r = facade(trendMusic(), null).analyzeTrends(USER);
        assertTrue(r.narrative().contains("synthwave"), r.narrative());
        assertTrue(r.narrative().contains("jazz"), r.narrative());
        assertTrue(r.narrative().endsWith(config.computedLabel()), r.narrative());
    }

    @Test
    void trendsFallBackWhenTheLlmFails() {
        TrendReport r = facade(trendMusic(), p -> { throw new LLMException("down"); }).analyzeTrends(USER);
        assertTrue(r.narrative().endsWith(config.computedLabel()), r.narrative());
    }

    @Test
    void anEmptyRangeIsMarkedAndTheOthersAreStillCompared() {
        FakeMusic music = trendMusic();
        music.artistsByRange.put("medium", List.of());

        TrendReport r = facade(music, null).analyzeTrends(USER);

        assertEquals(1, r.gaps().size());
        assertTrue(r.gaps().get(0).contains("medium"));
        assertEquals(List.of("synthwave"), r.addedGenres());
    }

    @Test
    void aRangeThatFailsToLoadIsAGapNotAnError() {
        FakeMusic music = trendMusic();
        music.artistsFailure = null;
        MemoryManager memory = memory();
        ToolManager tm = new ToolManager();
        SpotifyTool.allFor(new FakeMusic() {
            @Override public List<Artist> getTopArtists(String range) {
                if (range.equals("medium")) throw new MusicServiceException("Spotify returned 500");
                return music.artistsByRange.get(range);
            }
        }).forEach(tm::register);

        TrendReport r = facade(music, null, memory, tm).analyzeTrends(USER);

        assertTrue(r.gaps().contains(String.format(config.wording("trendRangeFailed"), "medium")), r.gaps().toString());
        r.gaps().forEach(g -> assertFalse(g.contains("500"), g));
        assertFalse(r.addedGenres().isEmpty());
    }

    @Test
    void aFailedTrendReadShowsNoRawErrorInTheGapsTheNarrativeOrThePrompt() {
        FakeMusic music = trendMusic();
        FakeMusic failing = new FakeMusic() {
            @Override public List<Artist> getTopArtists(String range) {
                if (range.equals("medium")) throw new MusicServiceException(RAW, 503);
                return music.artistsByRange.get(range);
            }
        };
        MemoryManager memory = memory();
        ToolManager tm = new ToolManager();
        SpotifyTool.allFor(failing).forEach(tm::register);
        List<String> prompts = new ArrayList<>();

        TrendReport r = facade(music, p -> { prompts.add(p); return "Your taste moved."; }, memory, tm).analyzeTrends(USER);

        assertFalse(r.gaps().isEmpty());
        r.gaps().forEach(MusicAgentFacadeTest::assertNothingRaw);
        prompts.forEach(MusicAgentFacadeTest::assertNothingRaw);
        assertNothingRaw(r.narrative());
        assertNothingRaw(facade(music, null, memory, tm).analyzeTrends(USER).narrative());
    }

    @Test
    void trendsWithAllRangesEmptyReportGapsAndSayThereIsNothingToCompare() {
        FakeMusic music = new FakeMusic();
        music.artistsByRange.replaceAll((k, v) -> List.of());
        TrendReport r = facade(music, null).analyzeTrends(USER);
        assertEquals(4, r.gaps().size(), "three empty ranges plus nothing to compare");
        assertNotNull(r.narrative());
        assertTrue(r.narrative().toLowerCase().contains("not enough"), r.narrative());
    }

    @Test
    void trendsPropagateAnExpiredToken() {
        FakeMusic music = trendMusic();
        music.artistsFailure = new AuthExpiredException("expired");
        assertThrows(AuthExpiredException.class, () -> facade(music, null).analyzeTrends(USER));
    }

    // ---- F3 ----

    private static Track named(String id, String title) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of());
    }

    @Test
    void searchUsesTheParsedQueryAndRanksTheRealResults() {
        FakeMusic music = new FakeMusic();
        music.searchResults = List.of(named("a", "Sunshine"), named("b", "Mellow Alternative"), named("a", "Sunshine"));
        LLMProvider llm = p -> "{\"keywords\":\"mellow alternative\",\"genre\":\"\",\"filters\":{},\"confidence\":0.9}";
        MusicAgentFacade f = facade(music, llm);

        List<Track> found = f.searchMusic("mellow alternative for studying");

        assertEquals(List.of("mellow alternative"), music.searches);
        assertEquals(config.searchResultLimit(), music.searchLimit);
        assertEquals(List.of("b", "a"), found.stream().map(Track::id).toList(), "ranked, duplicates dropped");
        assertNull(f.lastSearchLabel());
        assertNull(f.lastSearchNotice());
    }

    @Test
    void withNoLlmItSearchesTheTextAsTypedAndLabelsIt() {
        FakeMusic music = new FakeMusic();
        music.searchResults = List.of(named("a", "Lofi"));
        MusicAgentFacade f = facade(music, null);

        assertEquals(1, f.searchMusic("lofi beats").size());

        assertEquals(List.of("lofi beats"), music.searches);
        assertEquals(config.searchFallbackLabel(), f.lastSearchLabel());
    }

    @Test
    void aLowConfidenceParseSearchesTheOriginalTextAndLabelsIt() {
        FakeMusic music = new FakeMusic();
        music.searchResults = List.of(named("a", "Jazz"));
        MusicAgentFacade f = facade(music, p -> "{\"keywords\":\"guess\",\"confidence\":0.1}");

        f.searchMusic("rainy jazz");

        assertEquals(List.of("rainy jazz"), music.searches);
        assertEquals(config.searchFallbackLabel(), f.lastSearchLabel());
    }

    @Test
    void noResultsGivesAnEmptyListAndARelaxedQuerySuggestion() {
        FakeMusic music = new FakeMusic();
        LLMProvider llm = p -> "{\"keywords\":\"mellow\",\"genre\":\"shoegaze\","
                + "\"filters\":{\"year\":\"1987\"},\"confidence\":0.9}";
        MusicAgentFacade f = facade(music, llm);

        assertTrue(f.searchMusic("mellow shoegaze from 1987").isEmpty());

        String notice = f.lastSearchNotice();
        assertNotNull(notice);
        assertTrue(notice.contains("mellow shoegaze from 1987"), notice);
        assertTrue(notice.contains("\"mellow\""), "suggests the keywords alone: " + notice);
        assertEquals(List.of("mellow shoegaze year:1987", "mellow"), music.searches, "retried once");
    }

    @Test
    void anEmptyParsedSearchIsRetriedWithTheKeywordsAloneAndTheRetryResultsReturned() {
        FakeMusic music = new FakeMusic() {
            @Override public List<Track> search(String query, int limit) {
                super.search(query, limit);
                return query.equals("mellow") ? List.of(named("r", "Mellow Song")) : List.of();
            }
        };
        LLMProvider llm = p -> "{\"keywords\":\"mellow\",\"genre\":\"shoegaze\","
                + "\"filters\":{\"year\":\"1987\"},\"confidence\":0.9}";
        MusicAgentFacade f = facade(music, llm);

        List<Track> found = f.searchMusic("mellow shoegaze from 1987");

        assertEquals(List.of("mellow shoegaze year:1987", "mellow"), music.searches);
        assertEquals(List.of("r"), found.stream().map(Track::id).toList());
        assertNull(f.lastSearchNotice());
        assertNull(f.lastSearchLabel(), "a retry is not an LLM failure");
    }

    @Test
    void noRetryHappensWhenTheKeywordsAreTheWholeQuery() {
        FakeMusic music = new FakeMusic();
        assertTrue(facade(music, null).searchMusic("zzzz").isEmpty());
        assertEquals(List.of("zzzz"), music.searches);
    }

    @Test
    void relaxingAKeywordOnlyQueryDropsItsLastWord() {
        MusicAgentFacade f = facade(new FakeMusic(), null);
        assertEquals(java.util.Optional.of("rainy day"),
                f.suggestRelaxedQuery(new com.musicmind.domain.SearchQuery("rainy day jazz", null, Map.of(), 0)));
        assertEquals(java.util.Optional.empty(),
                f.suggestRelaxedQuery(new com.musicmind.domain.SearchQuery("jazz", null, Map.of(), 0)));
    }

    @Test
    void aNoticeWithNothingToRelaxStillGivesAHint() {
        FakeMusic music = new FakeMusic();
        MusicAgentFacade f = facade(music, null);
        assertTrue(f.searchMusic("zzzz").isEmpty());
        assertTrue(f.lastSearchNotice().contains(config.searchNoRelaxedHint()), f.lastSearchNotice());
    }

    @Test
    void theNoticeAndLabelAreResetByTheNextSearch() {
        FakeMusic music = new FakeMusic();
        MusicAgentFacade f = facade(music, null);
        f.searchMusic("zzzz");
        music.searchResults = List.of(named("a", "Ok"));
        f.searchMusic("ok");
        assertNull(f.lastSearchNotice());
    }

    @Test
    void aFailedSearchSaysWhy() {
        FakeMusic music = new FakeMusic();
        music.searchFailure = new MusicServiceException(RAW, 503);
        MusicServiceException e = assertThrows(MusicServiceException.class, () -> facade(music, null).searchMusic("jazz"));
        assertEquals(config.wording("searchFailed"), e.getMessage());
        assertNothingRaw(e.getMessage());
        assertSame(music.searchFailure, e.getCause(), "the original failure stays available as the cause");
    }

    @Test
    void aBlankQueryIsRejectedBeforeAnyCall() {
        FakeMusic music = new FakeMusic();
        assertThrows(IllegalArgumentException.class, () -> facade(music, null).searchMusic("   "));
        assertTrue(music.searches.isEmpty());
    }
}
