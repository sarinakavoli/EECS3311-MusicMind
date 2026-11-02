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
import com.musicmind.domain.RangeChange;
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
        assertEquals("Meets every constraint", badges.get(0).text());
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
    void trendColumnsFollowTheRangeOrderAndRespectTheLimits() {
        Map<String, List<Artist>> artists = new LinkedHashMap<>();
        artists.put("short", List.of(new Artist("1", "A", List.of("rock")), new Artist("2", "B", List.of())));
        artists.put("long", List.of(new Artist("3", "C", List.of("pop"))));
        Map<String, Map<String, Double>> genres = new LinkedHashMap<>();
        Map<String, Double> shortGenres = new LinkedHashMap<>();
        shortGenres.put("rock", 0.6);
        shortGenres.put("indie", 0.3);
        shortGenres.put("folk", 0.1);
        genres.put("short", shortGenres);
        TrendReport report = new TrendReport(artists, genres, List.of(), List.of(), List.of(), List.of(), List.of(), "n");

        List<ViewFormat.TrendColumn> columns = format.trendColumns(report, List.of("short", "medium", "long"), 1, 2);

        assertEquals(3, columns.size());
        assertEquals("short", columns.get(0).range());
        assertEquals(List.of("A"), columns.get(0).artists());
        assertEquals(List.of("rock", "indie"), columns.get(0).genres());
        assertEquals("genre data: 1 of 2 artists", columns.get(0).coverage());
        assertTrue(columns.get(1).artists().isEmpty());
        assertEquals("", columns.get(1).coverage());
        assertEquals(List.of("C"), columns.get(2).artists());
    }

    @Test
    void trendStepLinesNameArtistsFromTheReport() {
        Map<String, List<Artist>> artists = Map.of("long", List.of(new Artist("9", "Nine", List.of())));
        RangeChange step = new RangeChange("long", "medium", List.of("pop"), List.of(), List.of("9"), List.of());
        TrendReport report = new TrendReport(artists, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "n", List.of(step));

        List<String> lines = format.trendStepLines(report);

        assertEquals("From long to medium:", lines.get(0));
        assertTrue(lines.contains("  Genres added: pop"));
        assertTrue(lines.contains("  Genres dropped: (none)"));
        assertTrue(lines.contains("  Artists added: Nine"));
    }

    @Test
    void historyRowsShowTheRequestAndTheResult() {
        AgentSession session = new AgentSession("s-1", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        session.setStatus("done");
        session.setRequest(new AgentRequest("calm study mix", RequestKind.CREATE_PLAYLIST));
        session.setPlaylistId("pl9");

        ViewFormat.HistoryRow row = format.historyRows(List.of(session)).get(0);

        assertEquals("s-1", row.sessionId());
        assertEquals("2026-11-01 14:05", row.when());
        assertEquals("done", row.status());
        assertEquals("Create playlist: calm study mix", row.request());
        assertEquals("pl9", row.playlistId());
    }

    @Test
    void aSessionWithNoRequestOrPlaylistStillFormats() {
        AgentSession session = new AgentSession("s-2", Instant.parse("2026-11-01T14:05:00Z"), new IdleState());
        ViewFormat.HistoryRow row = format.historyRows(List.of(session)).get(0);
        assertEquals("", row.request());
        assertEquals("", row.playlistId());
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
    void progressTextShowsTheStateName() {
        assertEquals("planning...", format.progress("planning"));
    }

    @Test
    void aDecisionTraceIsExplainedAsStrategyAndConstraints() {
        DecisionTrace trace = new DecisionTrace("Balanced", Map.of("a", "why"), List.of("no duplicate tracks", "explicit tracks excluded"));

        assertEquals(List.of("Chosen with the Balanced strategy.", "- no duplicate tracks", "- explicit tracks excluded"),
                format.traceLines(trace));
    }

    @Test
    void noTraceGivesNoLines() {
        assertTrue(format.traceLines(null).isEmpty());
    }

    @Test
    void recommendationDetailShowsTheScoreAndFamiliarShare() {
        Recommendation rec = new Recommendation(track("a", 3, false), "by an artist new to you", 0.25, 0.8765);
        assertEquals("score 0.88, familiar 25%", format.recommendationDetail(rec));
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
    void thumbsWordingSurvivesTheFileEncoding() {
        assertTrue(config.wording("guiAccept").startsWith("\uD83D\uDC4D"));
        assertTrue(config.wording("guiReject").startsWith("\uD83D\uDC4E"));
    }

    @Test
    void aSavedVersionIsDescribedByNameSizeAndTime() {
        SnapshotInfo info = new SnapshotInfo(4, "pl9", "Mix", 12, Instant.parse("2026-11-01T14:05:00Z"));
        assertEquals("Mix (12 tracks), saved 2026-11-01 14:05", format.snapshotLine(info));
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
    void aFriendlyMessageIsKeptWhole() {
        String friendly = "Say what to change, for example \"remove explicit tracks\".";
        assertEquals("Could not search: " + friendly, format.failure("ActionSearch", new IllegalArgumentException(friendly)));
    }

    @Test
    void lengthTextIsAWholeNumberOfMinutesWithinTheLimit() {
        int max = config.parserMaxTargetMinutes();
        assertEquals(java.util.OptionalInt.of(45), format.parseMinutes("45"));
        assertEquals(java.util.OptionalInt.of(45), format.parseMinutes(" 45 "));
        assertEquals(java.util.OptionalInt.of(0), format.parseMinutes(""));
        assertEquals(java.util.OptionalInt.of(0), format.parseMinutes(null));
        assertEquals(java.util.OptionalInt.of(max), format.parseMinutes(String.valueOf(max)));
        assertTrue(format.parseMinutes(String.valueOf(max + 1)).isEmpty());
        assertTrue(format.parseMinutes("-5").isEmpty());
        assertTrue(format.parseMinutes("abc").isEmpty());
        assertTrue(format.parseMinutes("4.5").isEmpty());
        assertTrue(format.parseMinutes("99999999999999999999").isEmpty());
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
}
