package com.musicmind.app.view;

import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.state.IdleState;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.ValidationResult;
import com.musicmind.llm.LLMException;
import com.musicmind.memory.PersistenceException;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.RateLimitException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFormatTest {

    private final Config config = Config.of(Map.of());
    private final ViewFormat format = new ViewFormat(config, ZoneOffset.UTC);

    private static Track track(String id, int minutes, boolean explicit) {
        return new Track(id, "spotify:track:" + id, "T" + id, minutes * 60_000, explicit, List.of());
    }

    @Test
    void everyScreenHasMenuNameAndDescription() {
        Map<String, String> names = Map.of("profile", "Your Taste", "trends", "Trends", "search", "Find Songs",
                "discover", "Discover", "playlists", "Playlists", "preferences", "Your Rules", "history", "History");
        for (Map.Entry<String, String> screen : names.entrySet()) {
            assertEquals(screen.getValue(), format.w("Menu." + screen.getKey()));
            assertFalse(format.w("Desc." + screen.getKey()).isBlank(), screen.getKey());
        }
        assertEquals("Start listening to something new. Artists and songs you haven't heard yet, picked from your taste.",
                format.w("Desc.discover"));
    }

    @Test
    void durationShowsMinutesAndPaddedSeconds() {
        assertEquals("3:05", format.duration(185_000));
        assertEquals("0:09", format.duration(9_000));
        assertEquals("61:00", format.duration(3_660_000));
    }

    @Test
    void minutesRoundToTheNearest() {
        assertEquals(2, format.minutes(90_000));
        assertEquals(1, format.minutes(60_000));
        assertEquals(0, format.minutes(0));
    }

    @Test
    void genreRowsArePercentagesWithBarsRelativeToTheLargest() {
        Map<String, Double> histogram = new LinkedHashMap<>();
        histogram.put("rock", 0.5);
        histogram.put("pop", 0.3);
        histogram.put("jazz", 0.2);
        ListeningProfile profile = new ListeningProfile(histogram, List.of(), List.of(), Instant.EPOCH);

        List<ViewFormat.GenreRow> rows = format.genreRows(profile, 2);

        assertEquals(2, rows.size());
        assertEquals("rock", rows.get(0).name());
        assertEquals("50.0%", rows.get(0).percent());
        assertEquals(1.0, rows.get(0).bar(), 1e-9);
        assertEquals("30.0%", rows.get(1).percent());
        assertEquals(0.6, rows.get(1).bar(), 1e-9);
    }

    @Test
    void genreRowsOfAnEmptyHistoryAreEmpty() {
        assertTrue(format.genreRows(new ListeningProfile(Map.of(), List.of(), List.of(), Instant.EPOCH), 5).isEmpty());
    }

    @Test
    void artistNamesSkipBlankOnesAndStopAtTheLimit() {
        List<Artist> artists = List.of(new Artist("1", "A", List.of()), new Artist("2", " ", List.of()),
                new Artist("3", "C", List.of()), new Artist("4", "D", List.of()));
        assertEquals(List.of("A", "C"), format.artistNames(artists, 2));
    }

    @Test
    void aPassingPlaylistGetsOneGoodBadge() {
        List<ViewFormat.Badge> badges = format.badges(ValidationResult.ok(), List.of(track("a", 3, false)), 0);
        assertEquals(1, badges.size());
        assertEquals("Meets your rules", badges.get(0).text());
        assertFalse(badges.get(0).warning());
    }

    @Test
    void explicitTracksAreCounted() {
        ValidationResult result = ValidationResult.failed(List.of("x"),
                Map.of(PlaylistValidator.RULE_EXPLICIT, List.of("a", "b")));
        assertEquals("2 explicit tracks", format.badges(result, List.of(), 0).get(0).text());
        assertTrue(format.badges(result, List.of(), 0).get(0).warning());

        ValidationResult one = ValidationResult.failed(List.of("x"), Map.of(PlaylistValidator.RULE_EXPLICIT, List.of("a")));
        assertEquals("1 explicit track", format.badges(one, List.of(), 0).get(0).text());
    }

    @Test
    void durationOffTheTargetSaysHowFarAndWhichWay() {
        ValidationResult failed = ValidationResult.failed(List.of("x"), Map.of(PlaylistValidator.RULE_DURATION, List.of()));

        List<Track> over = List.of(track("a", 50, false), track("b", 46, false));
        assertEquals("over target by 6 min", format.badges(failed, over, 90).get(0).text());

        List<Track> under = List.of(track("a", 50, false), track("b", 30, false));
        assertEquals("under target by 10 min", format.badges(failed, under, 90).get(0).text());
    }

    @Test
    void duplicatesBlocksAndLengthLimitsGetBadges() {
        Map<String, List<String>> offending = new LinkedHashMap<>();
        offending.put(PlaylistValidator.RULE_DUPLICATES, List.of("a"));
        offending.put(PlaylistValidator.RULE_BLOCKED_ARTIST, List.of("a", "b", "c"));
        offending.put(PlaylistValidator.RULE_BLOCKED_TRACK, List.of("d"));
        offending.put(PlaylistValidator.RULE_MIN_LENGTH, List.of());
        offending.put(PlaylistValidator.RULE_MAX_LENGTH, List.of());
        List<String> texts = format.badges(ValidationResult.failed(List.of("x"), offending), List.of(), 0)
                .stream().map(ViewFormat.Badge::text).toList();

        assertEquals(List.of("1 duplicate track", "3 tracks by blocked artists", "1 blocked track",
                "too few tracks", "too many tracks"), texts);
    }

    @Test
    void rangeLabel_mapsSpotifyRanges() {
        assertEquals("Past month", format.rangeLabel("short"));
        assertEquals("Past 6 months", format.rangeLabel("medium"));
        assertEquals("Past year", format.rangeLabel("long"));
        assertEquals("Past week", format.rangeLabel("week"));
    }

    @Test
    void rangeLabel_unknownStaysRaw() {
        assertEquals("weekly", format.rangeLabel("weekly"));
        assertEquals("", format.rangeLabel(""));
        assertEquals(null, format.rangeLabel(null));
    }

    private static TrendReport tableReport() {
        Map<String, List<Artist>> artists = new LinkedHashMap<>();
        artists.put("short", List.of(new Artist("1", "Newbie", List.of("rock")), new Artist("2", "Steady", List.of())));
        artists.put("medium", List.of(new Artist("2", "Steady", List.of())));
        artists.put("long", List.of(new Artist("2", "Steady", List.of()), new Artist("3", "Gone", List.of("folk"))));
        Map<String, Map<String, Double>> genres = new LinkedHashMap<>();
        Map<String, Double> shortG = new LinkedHashMap<>();
        shortG.put("rock", 0.6);
        Map<String, Double> mediumG = new LinkedHashMap<>();
        mediumG.put("rock", 0.5);
        mediumG.put("pop", 0.3);
        Map<String, Double> longG = new LinkedHashMap<>();
        longG.put("folk", 0.4);
        longG.put("pop", 0.2);
        genres.put("short", shortG);
        genres.put("medium", mediumG);
        genres.put("long", longG);
        return new TrendReport(artists, genres, List.of(), List.of(), List.of(), List.of(), List.of(), "n");
    }

    @Test
    void trendTableRunsOldestToNewestWithGenresThenArtists() {
        ViewFormat.TrendTable table = format.trendTable(tableReport(), List.of("short", "medium", "long"), 10, 3);

        assertEquals(List.of("Past year", "Past 6 months", "Past month"), table.periods());
        List<String> labels = table.rows().stream().map(ViewFormat.TrendRow::label).toList();
        assertEquals(List.of("Genres", "rock", "pop", "folk", "Artists", "Newbie", "Steady", "Gone"), labels);
        assertTrue(table.rows().get(0).header());
        assertTrue(table.rows().get(4).header());
        assertFalse(table.rows().get(1).header());
    }

    @Test
    void trendTableTicksWhereTheItemIsAndSaysNewOrDropped() {
        ViewFormat.TrendTable table = format.trendTable(tableReport(), List.of("short", "medium", "long"), 10, 3);
        Map<String, ViewFormat.TrendRow> byLabel = new LinkedHashMap<>();
        table.rows().forEach(r -> byLabel.put(r.label(), r));

        assertEquals(List.of(false, true, true), byLabel.get("rock").present());
        assertEquals(ViewFormat.TrendChange.NEW, byLabel.get("rock").change());
        assertEquals("\u2191 new", byLabel.get("rock").changeText());
        assertEquals(List.of(true, false, false), byLabel.get("folk").present());
        assertEquals("\u2193 dropped", byLabel.get("folk").changeText());
        assertEquals(List.of(true, true, false), byLabel.get("pop").present());   // in the oldest, not the newest
        assertEquals(ViewFormat.TrendChange.DROPPED, byLabel.get("pop").change());
        assertEquals("", byLabel.get("Steady").changeText());
        assertEquals(ViewFormat.TrendChange.NONE, byLabel.get("Steady").change());
        assertEquals(List.of(false, false, true), byLabel.get("Newbie").present());
        assertEquals("\u2193 dropped", byLabel.get("Gone").changeText());
    }

    @Test
    void trendTableRespectsTheLimitsAndLeavesOutEmptySections() {
        ViewFormat.TrendTable limited = format.trendTable(tableReport(), List.of("short", "medium", "long"), 1, 1);
        assertEquals(List.of("Genres", "rock", "folk", "Artists", "Newbie", "Steady"),
                limited.rows().stream().map(ViewFormat.TrendRow::label).toList());   // one from each range
        TrendReport empty = new TrendReport(Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "n");
        assertTrue(format.trendTable(empty, List.of("short", "medium", "long"), 10, 3).rows().isEmpty());
    }

    @Test
    void trendTableCoverageNoteOnlyWhenSomeArtistsHaveNoGenres() {
        // Newbie and Gone have genres, Steady has none: 2 of 3
        assertEquals("Genres known for 2 of 3 artists",
                format.trendTable(tableReport(), List.of("short", "medium", "long"), 10, 3).coverage());
        Map<String, List<Artist>> all = Map.of("short", List.of(new Artist("1", "A", List.of("rock"))));
        TrendReport complete = new TrendReport(all, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "n");
        assertEquals("", format.trendTable(complete, List.of("short"), 10, 3).coverage());
    }

    @Test
    void generatedLineLeavesOutABlankRange() {
        Instant when = Instant.parse("2026-11-01T14:05:00Z");
        assertEquals("Updated 2026-11-01 14:05 \u00b7 Past month", format.generatedLine(when, "short"));
        assertEquals("Updated 2026-11-01 14:05", format.generatedLine(when, ""));
        assertEquals("Updated 2026-11-01 14:05", format.generatedLine(when, null));
        assertEquals("", format.generatedLine(null, "short"));
    }

    @Test
    void sessionLinesListThePlanStepsAndTheResult() {
        AgentSession session = new AgentSession("s-3", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        session.setStatus("done");
        session.setRequest(new AgentRequest("calm", RequestKind.CREATE_PLAYLIST));
        session.setPlanSteps(List.of("Understand", "Select"));
        session.setPlaylistId("pl9");

        List<String> lines = format.sessionLines(session);

        assertTrue(lines.contains("Request: calm"));
        assertTrue(lines.contains("Status: done"));
        assertTrue(lines.contains("Plan:"));
        assertTrue(lines.contains("  1. Understand"));
        assertTrue(lines.contains("  2. Select"));
        assertTrue(lines.contains("Result: playlist pl9"));
    }

    @Test
    void aSessionWithoutAPlaylistSaysNothingWasCreated() {
        AgentSession session = new AgentSession("s-4", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        List<String> lines = format.sessionLines(session);
        assertTrue(lines.contains("Result: no playlist was created or changed"));
        assertTrue(lines.contains("Plan: none recorded for this kind of session"));
    }

    @Test
    void anExpiredLoginAsksTheUserToLogInAgain() {
        assertEquals(config.wording("cliLoginExpired"), format.failure("ActionSearch", new AuthExpiredException("401 at https://x")));
    }

    @Test
    void otherFailuresNameTheActionAndKeepTheMessage() {
        assertEquals("Could not search: no network", format.failure("ActionSearch", new IllegalStateException("no network")));
    }

    @Test
    void aFailureWithoutAMessageStillReadsAsAnError() {
        String text = format.failure("ActionSearch", new NullPointerException());
        assertTrue(text.startsWith("Could not search"));
        assertFalse(text.contains("null"));
    }

    @Test
    void progressTextIsPlainWordsForEachRunStep() {
        assertEquals("Understanding your goal...", format.progress("planning"));
        assertEquals("Finding songs...", format.progress("retrieving"));
        assertEquals("Choosing the best ones...", format.progress("selecting"));
        assertEquals("Checking your rules...", format.progress("validating"));
        assertEquals("Saving to Spotify...", format.progress("executing"));
        assertEquals("Understanding your goal...", format.progress(" Planning "));
    }

    @Test
    void progressTextForAnUnknownStateIsWorking() {
        assertEquals("Working...", format.progress("done"));
        assertEquals("Working...", format.progress("whatever"));
        assertEquals("Working...", format.progress(""));
        assertEquals("Working...", format.progress(null));
    }

    @Test
    void waitingListShowsUpToFiveRowsThenScrolls() {
        assertEquals(0, format.waitingRowsShown(0));
        assertEquals(3, format.waitingRowsShown(3));
        assertEquals(5, format.waitingRowsShown(5));
        assertEquals(5, format.waitingRowsShown(12));
    }

    @Test
    void aDecisionTraceIsExplainedAsStrategyAndConstraints() {
        DecisionTrace trace = new DecisionTrace("Balanced", Map.of("a", "why"), List.of("no duplicate tracks", "explicit tracks excluded"));

        assertEquals(List.of("Chosen using a balance of artists you know and new ones.", "- no duplicate tracks",
                "- explicit tracks excluded"), format.traceLines(trace));
    }

    @Test
    void strategyNamesAreShownInPlainWords() {
        assertEquals("a balance of artists you know and new ones", format.strategyWords("BalancedStrategy"));
        assertEquals("a balance of artists you know and new ones", format.strategyWords("Balanced"));
        assertEquals("mostly new artists you have not heard yet", format.strategyWords("DiscoveryStrategy"));
        assertEquals("mostly artists you already listen to", format.strategyWords("FamiliarStrategy"));
        for (String name : new String[]{"BalancedStrategy", "DiscoveryStrategy", "FamiliarStrategy", "SomethingElseStrategy", null, ""}) {
            String words = format.strategyWords(name);
            assertFalse(words.isBlank());
            assertFalse(words.contains("Strategy"), words);
            assertFalse(format.traceLines(new DecisionTrace(name, Map.of(), List.of())).get(0).contains("Strategy"));
        }
    }

    @Test
    void unknownStrategyGetsGenericWordsNotItsClassName() {
        assertEquals("a method picked for your request", format.strategyWords("SomethingElseStrategy"));
        assertEquals("a method picked for your request", format.strategyWords(null));
    }

    private static AgentSession session(String id, String when, String text, RequestKind kind) {
        AgentSession s = new AgentSession(id, when == null ? null : Instant.parse(when), new IdleState());
        s.setRequest(new AgentRequest(text, kind));
        return s;
    }

    private List<AgentSession> activitySample() {
        return List.of(
                session("create", "2026-11-01T10:00:00Z", "calm", RequestKind.CREATE_PLAYLIST),
                session("modify", "2026-11-02T10:00:00Z", "remove slow songs", RequestKind.MODIFY_PLAYLIST),
                session("restore", "2026-11-03T10:00:00Z", "restore a saved version", RequestKind.MODIFY_PLAYLIST),
                session("recreate", "2026-11-04T10:00:00Z", "recreate a deleted playlist from a saved version", RequestKind.CREATE_PLAYLIST),
                session("taste", "2026-11-05T10:00:00Z", "view taste profile (medium)", RequestKind.ANALYZE_TASTE),
                session("discover", "2026-11-06T10:00:00Z", "discover", RequestKind.DISCOVER));
    }

    private static List<String> ids(List<AgentSession> list) {
        return list.stream().map(AgentSession::id).toList();
    }

    @Test
    void activityTypeOfASession() {
        List<AgentSession> s = activitySample();
        assertEquals(ViewFormat.ActivityType.CREATED, format.activityType(s.get(0)));
        assertEquals(ViewFormat.ActivityType.CHANGED, format.activityType(s.get(1)));
        assertEquals(ViewFormat.ActivityType.RESTORED, format.activityType(s.get(2)));
        assertEquals(ViewFormat.ActivityType.RESTORED, format.activityType(s.get(3)));
        assertEquals(ViewFormat.ActivityType.TASTE, format.activityType(s.get(4)));
        assertEquals(ViewFormat.ActivityType.OTHER, format.activityType(s.get(5)));
    }

    @Test
    void activityIsNewestFirstByDefaultAndOldestFirstOnRequest() {
        List<AgentSession> s = activitySample();
        assertEquals(List.of("discover", "recreate", "restore", "modify", "create"),
                ids(format.activity(s, ViewFormat.ActivityType.ALL, true, false)));
        assertEquals(List.of("create", "modify", "restore", "recreate", "discover"),
                ids(format.activity(s, ViewFormat.ActivityType.ALL, false, false)));
    }

    @Test
    void activityTypeFilters() {
        List<AgentSession> s = activitySample();
        assertEquals(List.of("create"), ids(format.activity(s, ViewFormat.ActivityType.CREATED, true, false)));
        assertEquals(List.of("modify"), ids(format.activity(s, ViewFormat.ActivityType.CHANGED, true, false)));
        assertEquals(List.of("recreate", "restore"), ids(format.activity(s, ViewFormat.ActivityType.RESTORED, true, false)));
    }

    @Test
    void tasteViewsAreHiddenUnlessAskedForByTypeOrByTheRoutineSwitch() {
        List<AgentSession> s = activitySample();
        assertFalse(ids(format.activity(s, ViewFormat.ActivityType.ALL, true, false)).contains("taste"));
        assertTrue(ids(format.activity(s, ViewFormat.ActivityType.ALL, true, true)).contains("taste"));
        assertEquals(List.of("taste"), ids(format.activity(s, ViewFormat.ActivityType.TASTE, true, false)));
    }

    @Test
    void sessionsWithoutADateGoLastNewestFirstAndFirstOldestFirst() {
        List<AgentSession> s = List.of(session("none", null, "x", RequestKind.DISCOVER),
                session("a", "2026-11-01T10:00:00Z", "y", RequestKind.DISCOVER));
        assertEquals(List.of("a", "none"), ids(format.activity(s, ViewFormat.ActivityType.ALL, true, false)));
        assertEquals(List.of("none", "a"), ids(format.activity(s, ViewFormat.ActivityType.ALL, false, false)));
    }

    @Test
    void activityTypeAndSortLabelsAreWorded() {
        assertEquals("All", format.activityTypeLabel(ViewFormat.ActivityType.ALL));
        assertEquals("Created", format.activityTypeLabel(ViewFormat.ActivityType.CREATED));
        assertEquals("Changed", format.activityTypeLabel(ViewFormat.ActivityType.CHANGED));
        assertEquals("Restored", format.activityTypeLabel(ViewFormat.ActivityType.RESTORED));
        assertEquals("Taste profile", format.activityTypeLabel(ViewFormat.ActivityType.TASTE));
        assertEquals("Newest first", format.w("SortNewest"));
        assertEquals("Oldest first", format.w("SortOldest"));
    }

    @Test
    void foundSongsAlreadyWaitingAreLeftOutOfTheResults() {
        List<Track> found = List.of(track("a", 3, false), track("b", 3, false), track("c", 3, false));
        List<Track> waiting = List.of(track("b", 3, false));
        assertEquals(List.of("a", "c"), format.withoutWaiting(found, waiting).stream().map(Track::id).toList());
        assertEquals(found, format.withoutWaiting(found, List.of()));
    }

    @Test
    void spotifyLinkOpensTheTrackPageOrIsAbsent() {
        assertEquals("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
                format.spotifyTrackUrl(new Track("4uLU6hMCjMI75M1A2tKUQC", "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "T", 1000, false, List.of())));
        assertEquals(null, format.spotifyTrackUrl(new Track("", "", "T", 1000, false, List.of())));
        assertEquals(null, format.spotifyTrackUrl(new Track("a/../b", "", "T", 1000, false, List.of())));
        assertEquals(null, format.spotifyTrackUrl(null));
    }

    @Test
    void dialogHeightIsCappedToTheScreen() {
        assertEquals(700, UiParts.fitHeight(700, 900), 0.01);
        assertEquals(810, UiParts.fitHeight(1000, 900), 0.01);   // 90% of the screen
        assertEquals(360, UiParts.fitHeight(700, 400), 0.01);
    }

    @Test
    void noTraceGivesNoLines() {
        assertTrue(format.traceLines(null).isEmpty());
    }

    @Test
    void mixLabel_roundsAndSums100() {
        assertEquals("Mix: 60% new artists, 40% artists you know", format.mixLabel(0.4));
        assertEquals("Mix: 67% new artists, 33% artists you know", format.mixLabel(0.333));
        assertEquals("Mix: 29% new artists, 71% artists you know", format.mixLabel(0.706));
    }

    @Test
    void mixLabel_extremes() {
        assertEquals("Mix: 0% new artists, 100% artists you know", format.mixLabel(1.0));
        assertEquals("Mix: 100% new artists, 0% artists you know", format.mixLabel(0.0));
        assertEquals("Mix: 100% new artists, 0% artists you know", format.mixLabel(-3));
        assertEquals("Mix: 0% new artists, 100% artists you know", format.mixLabel(7));
    }

    @Test
    void mixLabel_defaultWhenNoSavedValue() {
        double fallback = config.discoverDefaultFamiliarRatio();
        assertEquals(format.mixLabel(fallback), format.mixLabel(java.util.OptionalDouble.empty()));
        assertEquals(format.mixLabel(0.4), format.mixLabel(java.util.OptionalDouble.of(0.4)));
        assertEquals(format.mixLabel(fallback), format.mixLabel(Double.NaN));
        assertFalse(format.mixLabel(java.util.OptionalDouble.empty()).contains("NaN"));
    }

    @Test
    void familiarityLabel_knownAndNew() {
        assertEquals("Artist you know", format.familiarityLabel(1.0));
        assertEquals("Artist you know", format.familiarityLabel(1.5));
        assertEquals("New artist for you", format.familiarityLabel(0.0));
        assertEquals("New artist for you", format.familiarityLabel(0.99));
    }

    @Test
    void playlistTitleShowsNameTrackCountAndMinutes() {
        Playlist playlist = new Playlist("p1", "Mix", List.of(track("a", 3, false), track("b", 4, false)));
        assertEquals("Mix: 2 tracks, 7 min", format.playlistTitle(playlist));
    }

    @Test
    void aTimestampIsReadableInTheChosenZone() {
        assertEquals("2026-11-02 14:32", format.timestamp(Instant.parse("2026-11-02T14:32:41.797014Z")));
        ViewFormat eastern = new ViewFormat(config, java.time.ZoneId.of("America/Toronto"));
        assertEquals("2026-11-02 09:32", eastern.timestamp(Instant.parse("2026-11-02T14:32:41.797014Z")));
    }

    @Test
    void iconWordingSurvivesTheFileEncoding() {
        assertTrue(config.wording("guiLikeIcon").startsWith("\u2665"));
        assertTrue(config.wording("guiNotForMeIcon").startsWith("\u2298"));
    }

    @Test
    void aSpotifyErrorNeverShowsTheStatusUrlOrBody() {
        String raw = "Spotify API error (HTTP 500) on GET https://api.spotify.com/v1/me/top/artists: {\"error\":{\"status\":500}}";
        String text = format.failure("ActionSearch", new MusicServiceException(raw, 500));

        assertEquals("Could not search: " + config.wording("playlistCallFailed"), text);
        assertFalse(text.contains("500") || text.contains("https") || text.contains("{"));
    }

    @Test
    void aRateLimitAsksTheUserToWait() {
        String text = format.failure("ActionSearch", new RateLimitException("Spotify rate limit hit on /v1/search", 5));
        assertEquals("Could not search: " + format.w("RateLimited"), text);
    }

    @Test
    void anLlmOrDatabaseFailureGetsItsOwnWording() {
        assertEquals("Could not search: " + format.w("LlmFailed"),
                format.failure("ActionSearch", new LLMException("The OpenAI request failed: HTTP 429 {\"error\":1}")));
        assertEquals("Could not search: " + format.w("DatabaseFailed"),
                format.failure("ActionSearch", new PersistenceException("connection refused to jdbc:postgresql://x", new RuntimeException())));
    }

    @Test
    void aMessageThatLooksRawIsReplacedByAGenericOne() {
        assertEquals("Could not search: " + format.w("UnknownError"),
                format.failure("ActionSearch", new IllegalStateException("bad reply from https://x.example/y")));
        assertEquals("Could not search: " + format.w("UnknownError"),
                format.failure("ActionSearch", new IllegalStateException("{\"a\":1}")));
    }

    @Test
    void aProgrammingErrorNeverShowsItsMessage() {
        String unknown = "Could not search: " + format.w("UnknownError");
        String npe = "Cannot invoke \"com.musicmind.memory.MemoryManager.loadPreferences(String)\" because \"this.memory\" is null";
        assertEquals(unknown, format.failure("ActionSearch", new NullPointerException(npe)));
        assertEquals(unknown, format.failure("ActionSearch", new ClassCastException("class A cannot be cast to class B")));
        assertEquals(unknown, format.failure("ActionSearch", new RuntimeException("plain text but not deliberate")));
    }

    @Test
    void aDeliberateExceptionWithStackLikeTextIsStillReplaced() {
        String unknown = "Could not search: " + format.w("UnknownError");
        assertEquals(unknown, format.failure("ActionSearch", new IllegalStateException(
                "Cannot invoke \"com.musicmind.memory.MemoryManager.loadPreferences(String)\" because \"this.memory\" is null")));
        assertEquals(unknown, format.failure("ActionSearch", new IllegalArgumentException("java.lang.IllegalStateException: x")));
        assertEquals(unknown, format.failure("ActionSearch", new IllegalStateException("failed at com.foo.Bar.run(Bar.java:12)")));
    }

    @Test
    void aFriendlyMessageFromAnAppExceptionIsKept() {
        assertEquals("Could not search: Pick a playlist first.",
                format.failure("ActionSearch", new com.musicmind.agent.ClarificationNeededException("Pick a playlist first.")));
    }

    @Test
    void aFriendlyMessageIsKeptWhole() {
        String friendly = "Say what to change, for example \"remove explicit tracks\".";
        assertEquals("Could not search: " + friendly, format.failure("ActionSearch", new IllegalArgumentException(friendly)));
    }

    private static void refused(Runnable call) {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, call::run);
        assertFalse(e.getMessage() == null || e.getMessage().isBlank());
    }

    @Test
    void parseHoursMinutes_blankIsFromGoal() {
        assertEquals(0, format.parseHoursMinutes("", ""));
        assertEquals(0, format.parseHoursMinutes("0", "0"));   // the Create dialog starts with 0 in both boxes
        assertEquals(0, format.parseHoursMinutes("0", ""));
        assertEquals(0, format.parseHoursMinutes(null, null));
        assertEquals(0, format.parseHoursMinutes("0", "0"));
        assertEquals(0, format.parseHoursMinutes(" ", "0"));
    }

    @Test
    void parseHoursMinutes_oneHourThirty_is90() {
        assertEquals(90, format.parseHoursMinutes("1", "30"));
        assertEquals(60, format.parseHoursMinutes(" 1 ", ""));
        assertEquals(45, format.parseHoursMinutes("", "45"));
    }

    @Test
    void parseHoursMinutes_minutesOver59_refused() {
        refused(() -> format.parseHoursMinutes("1", "75"));
        refused(() -> format.parseHoursMinutes("", "60"));
    }

    @Test
    void parseHoursMinutes_letters_refused() {
        refused(() -> format.parseHoursMinutes("a", "10"));
        refused(() -> format.parseHoursMinutes("1", "x"));
        refused(() -> format.parseHoursMinutes("-1", "10"));
        refused(() -> format.parseHoursMinutes("1", "-5"));
        refused(() -> format.parseHoursMinutes("1.5", ""));
        refused(() -> format.parseHoursMinutes("99999999999999999999", ""));
    }

    @Test
    void parseHoursMinutes_outOfRange_refused() {
        int maxHours = config.parserMaxTargetMinutes() / 60;
        assertEquals(config.parserMaxTargetMinutes(), format.parseHoursMinutes(String.valueOf(maxHours),
                String.valueOf(config.parserMaxTargetMinutes() % 60)));
        refused(() -> format.parseHoursMinutes(String.valueOf(maxHours + 1), "0"));
        refused(() -> format.parseHoursMinutes("1000", "0"));
    }

    @Test
    void parseHoursMinutes_belowPlannerMinimum_refusedWithTheRange() {
        ViewFormat strict = new ViewFormat(config.withTunables(Map.of("parser.minTargetMinutes", "5")), ZoneOffset.UTC);
        assertEquals(5, strict.parseHoursMinutes("", "5"));
        refused(() -> strict.parseHoursMinutes("", "4"));
        try {
            strict.parseHoursMinutes("0", "1");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("between 5 and"), e.getMessage());
        }
    }

    @Test
    void candidateAlreadyWaiting_saysSoInsteadOfAdded() {
        assertEquals("Idol is already waiting for your next playlist.", format.candidateAlreadyWaiting("Idol"));
    }

    @Test
    void waitingCount_isShownNextToTheResultsHeading() {
        assertEquals("1 waiting", format.waitingCount(1));
        assertEquals("3 waiting", format.waitingCount(3));
        assertEquals("", format.waitingCount(0));
    }

    @Test
    void aCancelledNegotiationIsShownAsItsOwnMessageWithoutCouldNot() {
        String message = config.wording("negotiationCancelled");
        assertEquals(message, format.failure("ActionCreate", new NegotiationCancelledException(message)));
    }

    @Test
    void aCancelledNegotiationWithoutAMessageUsesTheStandardWording() {
        assertEquals(config.wording("negotiationCancelled"), format.failure("ActionCreate", new NegotiationCancelledException(null)));
    }

    private static Track named(String title, String... artists) {
        return new Track("x", "spotify:track:x", title, 1000, false, List.of(), List.of(artists));
    }

    @Test
    void artists_joinsNames() {
        assertEquals("Ado, Yoasobi", format.artists(named("Song", "Ado", "Yoasobi")));
    }

    @Test
    void artists_noNames_isEmpty() {
        assertEquals("", format.artists(track("1", 3, false)));
    }

    @Test
    void trackTitleLine_noArtists_hasNoSeparator() {
        assertEquals("T1", format.trackTitleLine(track("1", 3, false)));
        assertEquals("Song - Ado", format.trackTitleLine(named("Song", "Ado")));
    }

    @Test
    void candidateAdded_includesRunningTotal() {
        assertEquals("Added All the Old Things. 2 songs are waiting for your next playlist.",
                format.candidateAdded("All the Old Things", 2));
        assertEquals("Added Idol. 1 song is waiting for your next playlist.", format.candidateAdded("Idol", 1));
    }

    @Test
    void preferenceError_isPlain() {
        String raw = config.wording("feedbackBadTrackId");
        String shown = format.blockFailure(new IllegalArgumentException(raw));
        assertFalse(shown.contains("22-character"), shown);
        assertFalse(shown.toLowerCase(java.util.Locale.ROOT).contains(" id"), shown);
        assertFalse(shown.isBlank());
        assertEquals(format.blockFailure(new IllegalArgumentException("x")), shown);
    }

    @Test
    void blockFailure_keepsTheStoredBlocksMessage() {
        String message = config.wording("prefsUnavailable");
        assertEquals(message, format.blockFailure(new IllegalStateException(message)));
    }

    @Test
    void blockedArtistName_neverShowsARawId() {
        String id = "4Z8W4fKeB5YxbusRsdQVPb";
        assertEquals("Ebi", format.blockedArtistName(id, "Ebi"));
        assertEquals(format.w("BlockedArtistUnknown"), format.blockedArtistName(id, id));
        assertEquals(format.w("BlockedArtistUnknown"), format.blockedArtistName(id, " "));
        assertEquals(format.w("BlockedArtistUnknown"), format.blockedArtistName(id, null));
    }

    @Test
    void blockedSongName_usesTheTitleOrAPlainPhrase() {
        assertEquals("Hello", format.blockedSongName("Hello"));
        assertEquals(format.w("BlockedSongUnknown"), format.blockedSongName(null));
        assertFalse(format.w("BlockedSongUnknown").contains("id"));
    }

    @Test
    void firstN_capsTheLookupsAndKeepsOrder() {
        assertEquals(List.of("a", "b"), ViewFormat.firstN(List.of("a", "b", "c"), 2));
        assertEquals(List.of("a"), ViewFormat.firstN(List.of("a"), 20));
        assertEquals(List.of(), ViewFormat.firstN(List.of("a"), 0));
        assertEquals(List.of(), ViewFormat.firstN(List.of("a"), -1));
    }

    @Test
    void isChanged_ignoresSpacesCaseOrderBlanksAndRepeats() {
        assertFalse(ViewFormat.isChanged("indie, jazz", "indie, jazz"));
        assertFalse(ViewFormat.isChanged("indie, jazz", "  Jazz ,indie,, "));
        assertFalse(ViewFormat.isChanged("", "  "));
        assertFalse(ViewFormat.isChanged(null, ""));
        assertFalse(ViewFormat.isChanged("jazz", "jazz, jazz"));
        assertTrue(ViewFormat.isChanged("indie, jazz", "indie"));
        assertTrue(ViewFormat.isChanged("", "pop"));
        assertTrue(ViewFormat.isChanged("pop", ""));
    }

    @Test
    void normalisedList_trimsLowercasesAndDropsBlanks() {
        assertEquals(List.of("indie", "jazz"), ViewFormat.normalisedList(" Indie , jazz,,"));
        assertEquals(List.of(), ViewFormat.normalisedList(null));
    }

    @Test
    void shareChanged_comparesAtTheTwoDecimalsSaved() {
        assertFalse(ViewFormat.shareChanged(0.7, 0.7));
        assertFalse(ViewFormat.shareChanged(0.7, 0.7004));
        assertTrue(ViewFormat.shareChanged(0.7, 0.75));
    }

    @Test
    void artistMatchesLine_saysHowManyAndToChoose() {
        assertEquals(format.w("ArtistMatchesMany", 3, "Ebi"), format.artistMatchesLine("Ebi", 3));
        assertEquals(format.w("ArtistMatchesOne", "Ebi"), format.artistMatchesLine("Ebi", 1));
        assertEquals(format.w("ArtistMatchesNone", "Ebi"), format.artistMatchesLine("Ebi", 0));
        assertEquals(true, format.artistMatchesLine("Ebi", 3).contains("3"));
    }

    @Test
    void preferencesWording_matchesTheControls() {
        assertFalse(format.w("Example.preferences").toLowerCase(java.util.Locale.ROOT).contains("save"));
        assertEquals(true, format.w("Example.preferences").toLowerCase(java.util.Locale.ROOT).contains("block"));
    }

    @Test
    void artistPosition_countsOnlyRepeatedNames() {
        assertEquals("1 of 3", format.artistPosition(1, 3));
        assertEquals("", format.artistPosition(1, 1));
    }

    @Test
    void blankSongSearch_hasAMessage() {
        assertFalse(format.w("SongBlank").isBlank());
    }

    @Test
    void versionChange_counts() {
        assertEquals("27 \u2192 22 songs", format.versionChange(27, 22));
    }

    @Test
    void versionChange_same() {
        assertEquals("27 songs (same songs as the version before)", format.versionChange(27, 27));
    }

    @Test
    void isRoutine_profileViewTrue_createFalse() {
        AgentSession profile = new AgentSession("a", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        profile.setRequest(new AgentRequest("view taste profile (medium)", RequestKind.ANALYZE_TASTE));
        AgentSession create = new AgentSession("b", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        create.setRequest(new AgentRequest("calm", RequestKind.CREATE_PLAYLIST));
        AgentSession bare = new AgentSession("c", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());

        assertTrue(format.isRoutine(profile));
        assertFalse(format.isRoutine(create));
        assertFalse(format.isRoutine(bare));
    }

    @Test
    void versionsAreGroupedByPlaylistWithTheChangeFromTheVersionBefore() {
        Instant t = Instant.parse("2026-11-01T14:05:00Z");
        List<SnapshotInfo> list = List.of(
                new SnapshotInfo(5, "a", "Mix A", 22, t.plusSeconds(300)),
                new SnapshotInfo(4, "b", "Mix B", 10, t.plusSeconds(200)),
                new SnapshotInfo(3, "a", "Mix A", 27, t.plusSeconds(100)),
                new SnapshotInfo(2, "a", "Mix A", 27, t));

        List<ViewFormat.VersionGroup> groups = format.versionGroups(list);

        assertEquals(2, groups.size());
        assertEquals("Mix A", groups.get(0).name());
        assertEquals(List.of("27 \u2192 22 songs", "27 songs (same songs as the version before)", "27 songs"),
                groups.get(0).versions().stream().map(ViewFormat.VersionLine::change).toList());
        assertEquals("Mix B", groups.get(1).name());
        assertEquals("10 songs", groups.get(1).versions().get(0).change());
    }

    @Test
    void aProfileViewReadsAsItsRangeNotItsRawRequest() {
        AgentSession profile = new AgentSession("a", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        profile.setRequest(new AgentRequest("view taste profile (medium)", RequestKind.ANALYZE_TASTE));
        AgentSession create = new AgentSession("b", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        create.setRequest(new AgentRequest("calm study mix", RequestKind.CREATE_PLAYLIST));

        assertEquals("Past 6 months", format.timelineText(profile));
        assertEquals("calm study mix", format.timelineText(create));
        assertEquals("Taste profile", format.kindLabel(profile));
    }

    @Test
    void statusFlagIsBlankForADoneRun() {
        AgentSession done = new AgentSession("a", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        done.setStatus("done");
        AgentSession failed = new AgentSession("b", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        failed.setStatus("failed");
        assertEquals("", format.statusFlag(done));
        assertEquals("failed", format.statusFlag(failed));
    }

    // ---- playlist song counts (Spotify's list total lags after a playlist is created or changed) ----

    private static com.musicmind.domain.PlaylistSummary listed(String id, int total) {
        return new com.musicmind.domain.PlaylistSummary(id, "Name " + id, total);
    }

    @Test
    void mergeTrackCounts_ownReadWinsForAPlaylistWeCreatedOrOpened() {
        List<com.musicmind.domain.PlaylistSummary> merged = format.mergeTrackCounts(
                List.of(listed("new", 0), listed("other", 12)), Map.of("new", 7));

        assertEquals(7, merged.get(0).trackCount(), "Slow Dance had 7 songs, not 0");
        assertEquals("Name new", merged.get(0).name());
        assertEquals(12, merged.get(1).trackCount(), "an unopened playlist keeps Spotify's total");
    }

    @Test
    void mergeTrackCounts_noKnownCountsLeavesTheListAlone() {
        List<com.musicmind.domain.PlaylistSummary> list = List.of(listed("a", 3));
        assertEquals(list, format.mergeTrackCounts(list, Map.of()));
    }

    @Test
    void mergeTrackCounts_spotifysTotalIsUsedOnceItIsAtLeastOurCount() {
        Map<String, Integer> known = Map.of("same", 7, "more", 7);
        List<com.musicmind.domain.PlaylistSummary> merged = format.mergeTrackCounts(
                List.of(listed("same", 7), listed("more", 9)), known);

        assertEquals(7, merged.get(0).trackCount());
        assertEquals(9, merged.get(1).trackCount(), "edited elsewhere: Spotify's higher total is shown");
    }

    @Test
    void countsStillLagging_keptOnlyWhileSpotifysTotalIsBelowOurs() {
        Map<String, Integer> known = Map.of("below", 7, "equal", 21, "above", 4, "gone", 4);
        Map<String, Integer> left = format.countsStillLagging(
                List.of(listed("below", 0), listed("equal", 21), listed("above", 9)), known);

        assertEquals(Map.of("below", 7), left);
    }

    @Test
    void joinDetails_putsTheNonBlankPartsOnOneLine() {
        assertEquals("Showing songs for the words as typed \u00b7 Artists found: Harry Styles",
                ViewFormat.joinDetails(ViewFormat.withoutFullStop("Showing songs for the words as typed."), null, " ", "Artists found: Harry Styles"));
        assertEquals("", ViewFormat.joinDetails(null, ""));
        assertEquals("Dr.", ViewFormat.joinDetails("Dr."));
        assertEquals("a", ViewFormat.withoutFullStop("a."));
        assertEquals("a", ViewFormat.withoutFullStop("a"));
        assertEquals(null, ViewFormat.withoutFullStop(null));
        assertEquals("Only one", ViewFormat.joinDetails("Only one"));
    }

    @Test
    void whyTheseSongs_listsTheTraceThenOneReasonPerSong() {
        Track a = new Track("a", "u", "Alpha", 1000, false, List.of(), List.of("X"));
        Track b = new Track("b", "u", "Beta", 1000, false, List.of(), List.of("Y"));
        String text = format.whyTheseSongs(List.of("Strategy: balanced"),
                List.of(new Recommendation(a, "New artist, from your top songs.", 0, 1), new Recommendation(b, " ", 1, 1)));

        assertEquals("Strategy: balanced\n\nAlpha: New artist, from your top songs.\nBeta: " + format.w("WhyUnavailable"), text);
        assertEquals("Alpha: r", format.whyTheseSongs(List.of(), List.of(new Recommendation(a, "r", 0, 1))));
    }
}
