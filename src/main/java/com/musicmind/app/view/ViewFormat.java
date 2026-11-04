package com.musicmind.app.view;

import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.state.SessionRecord;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.RangeChange;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.ValidationResult;
import com.musicmind.llm.LLMException;
import com.musicmind.memory.PersistenceException;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.RateLimitException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the GUI shows, worked out as plain text and numbers, so the views hold no logic and this class can
 * be tested without JavaFX. All wording comes from {@code musicmind.properties} through {@link Config}.
 */
public final class ViewFormat {

    /** One line of the genre breakdown: the genre, its share as text, and the bar length (0..1, longest = 1). */
    public record GenreRow(String name, String percent, double bar) { }

    /** A short status label for a playlist; {@code warning} marks a constraint that is not met. */
    public record Badge(String text, boolean warning) { }

    /** One time range of the Trends view. */
    public record TrendColumn(String range, List<String> artists, List<String> genres, String coverage) { }

    /** One line of the History list. */
    public record HistoryRow(String sessionId, String when, String status, String request, String playlistId) { }

    /** A message with a web address, an HTTP status or a JSON body in it is not fit to show. */
    private static final Pattern LOOKS_RAW = Pattern.compile("://|HTTP\\s*\\d{3}|[{}]");

    private final Config config;
    private final DateTimeFormatter timeFormat;

    public ViewFormat(Config config, ZoneId zone) {
        this.config = config;
        this.timeFormat = DateTimeFormatter.ofPattern(config.wording("guiTimeFormat")).withZone(zone);
    }

    public ViewFormat(Config config) {
        this(config, ZoneId.systemDefault());
    }

    public Config config() {
        return config;
    }

    /** The wording for {@code text.gui<key>}, filled in with {@code args}. */
    public String w(String key, Object... args) {
        String template = config.wording("gui" + key);
        return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
    }

    /** The wording for {@code text.cli<key>}, shared with the command line. */
    private String cli(String key, Object... args) {
        String template = config.wording("cli" + key);
        return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
    }

    /** A Spotify time range in plain words ({@code short} to Past month); any other value stays as it is. */
    public String rangeLabel(String range) {
        return switch (range == null ? "" : range) {
            case "short", "medium", "long" -> w("Range." + range);
            default -> range;
        };
    }

    /** "Updated <time> . <range>" for a profile; the range is left out when blank, and nothing when there is no time. */
    public String generatedLine(java.time.Instant generatedAt, String range) {
        if (generatedAt == null) {
            return "";
        }
        String when = timestamp(generatedAt);
        return range == null || range.isBlank() ? w("ProfileUpdated", when) : w("ProfileUpdatedRange", when, rangeLabel(range));
    }

    /** A track length as {@code m:ss}. */
    public String duration(int durationMs) {
        int seconds = Math.round(durationMs / 1000f);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The artist names of a track joined by ", "; empty when the track has none. */
    public String artists(Track track) {
        return track.artistNames().stream().filter(n -> n != null && !n.isBlank()).collect(Collectors.joining(", "));
    }

    /** The line after adding a song: its title and how many songs now wait for the next playlist. */
    public String candidateAdded(String title, int total) {
        return w(total == 1 ? "CandidateAddedOne" : "CandidateAddedMany", title, total);
    }

    /** The title, then " - " and the artists when there are any. */
    public String trackTitleLine(Track track) {
        String artists = artists(track);
        return artists.isEmpty() ? track.title() : w("TrackTitleLine", track.title(), artists);
    }

    /** Milliseconds as whole minutes, rounded to the nearest. */
    public long minutes(long durationMs) {
        return Math.round(durationMs / 60_000.0);
    }

    /** The genre breakdown, largest first, at most {@code limit} rows. */
    public List<GenreRow> genreRows(ListeningProfile profile, int limit) {
        List<Map.Entry<String, Double>> ranked = profile.rankedGenres(limit);
        double largest = ranked.isEmpty() ? 0 : ranked.get(0).getValue();
        List<GenreRow> rows = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ranked) {
            double bar = largest <= 0 ? 0 : entry.getValue() / largest;
            rows.add(new GenreRow(entry.getKey(), String.format(Locale.ROOT, "%.1f%%", entry.getValue() * 100), bar));
        }
        return rows;
    }

    /** Names of the first {@code limit} artists that have one. */
    public List<String> artistNames(List<Artist> artists, int limit) {
        return artists.stream().map(Artist::name).filter(n -> n != null && !n.isBlank()).limit(limit).toList();
    }

    /**
     * The badges of a validation result. Rules are taken from {@link ValidationResult#offending()}; the
     * duration badge says how far the playlist is from {@code targetMinutes} and in which direction.
     */
    public List<Badge> badges(ValidationResult result, List<Track> tracks, int targetMinutes) {
        if (result.passed()) {
            return List.of(new Badge(w("BadgeOk"), false));
        }
        List<Badge> badges = new ArrayList<>();
        Map<String, List<String>> offending = result.offending();
        addCounted(badges, offending, PlaylistValidator.RULE_EXPLICIT, "BadgeExplicit");
        addCounted(badges, offending, PlaylistValidator.RULE_DUPLICATES, "BadgeDuplicate");
        addCounted(badges, offending, PlaylistValidator.RULE_BLOCKED_ARTIST, "BadgeBlockedArtist");
        addCounted(badges, offending, PlaylistValidator.RULE_BLOCKED_TRACK, "BadgeBlockedTrack");
        if (offending.containsKey(PlaylistValidator.RULE_DURATION)) {
            long actualMs = tracks.stream().mapToLong(Track::durationMs).sum();
            long difference = actualMs - targetMinutes * 60_000L;
            long minutes = Math.max(1, Math.round(Math.abs(difference) / 60_000.0));
            badges.add(new Badge(w(difference > 0 ? "BadgeOver" : "BadgeUnder", minutes), true));
        }
        if (offending.containsKey(PlaylistValidator.RULE_MIN_LENGTH)) {
            badges.add(new Badge(w("BadgeTooFew"), true));
        }
        if (offending.containsKey(PlaylistValidator.RULE_MAX_LENGTH)) {
            badges.add(new Badge(w("BadgeTooMany"), true));
        }
        return badges;
    }

    private void addCounted(List<Badge> badges, Map<String, List<String>> offending, String rule, String key) {
        List<String> ids = offending.get(rule);
        if (ids != null && !ids.isEmpty()) {
            badges.add(new Badge(w(key + (ids.size() == 1 ? "One" : "Many"), ids.size()), true));
        }
    }

    /** One column per range in {@code ranges}, in that order; a range with no data has empty lists. */
    public List<TrendColumn> trendColumns(TrendReport report, List<String> ranges, int artistsShown, int genresShown) {
        List<TrendColumn> columns = new ArrayList<>();
        for (String range : ranges) {
            List<Artist> artists = report.artistsByRange().getOrDefault(range, List.of());
            List<String> genres = report.genresByRange().getOrDefault(range, Map.of()).keySet().stream()
                    .limit(genresShown).toList();
            String coverage = artists.isEmpty() ? ""
                    : w("TrendCoverage",
                            report.artistsWithGenres(range), report.artistCount(range));
            columns.add(new TrendColumn(rangeLabel(range), artistNames(artists, artistsShown), genres, coverage));
        }
        return columns;
    }

    /** What changed from each range to the next, then overall, as lines of text. */
    public List<String> trendStepLines(TrendReport report) {
        List<String> lines = new ArrayList<>();
        for (RangeChange step : report.steps()) {
            lines.add(w("TrendStep", rangeLabel(step.from()), rangeLabel(step.to())));
            changeLines(lines, step.addedGenres(), step.droppedGenres(), names(report, step.addedArtistIds()),
                    names(report, step.droppedArtistIds()));
        }
        List<RangeChange> steps = report.steps();
        lines.add(steps.isEmpty() ? w("TrendOverall")
                : w("TrendStep", rangeLabel(steps.get(0).from()), rangeLabel(steps.get(steps.size() - 1).to())));
        changeLines(lines, report.addedGenres(), report.droppedGenres(), names(report, report.addedArtistIds()),
                names(report, report.droppedArtistIds()));
        return lines;
    }

    private void changeLines(List<String> lines, List<String> genresAdded, List<String> genresDropped,
                             List<String> artistsAdded, List<String> artistsDropped) {
        lines.add("  " + w("GenresAdded") + ": " + listOrNone(genresAdded));
        lines.add("  " + w("GenresDropped") + ": " + listOrNone(genresDropped));
        lines.add("  " + w("ArtistsAdded") + ": " + listOrNone(artistsAdded));
        lines.add("  " + w("ArtistsDropped") + ": " + listOrNone(artistsDropped));
    }

    private String listOrNone(List<String> items) {
        return items.isEmpty() ? w("NothingChanged") : String.join(", ", items);
    }

    private static List<String> names(TrendReport report, List<String> ids) {
        Map<String, String> byId = new LinkedHashMap<>();
        report.artistsByRange().values().forEach(list -> list.forEach(a -> byId.put(a.id(), a.name())));
        return ids.stream().map(id -> byId.getOrDefault(id, id)).toList();
    }

    /** The rows of the History list, in the order given. */
    public List<HistoryRow> historyRows(List<AgentSession> sessions) {
        List<HistoryRow> rows = new ArrayList<>();
        for (AgentSession s : sessions) {
            String request = s.request() == null ? ""
                    : config.wording("guiKind." + s.request().kind().name()) + ": " + s.request().rawText();
            rows.add(new HistoryRow(s.id(), s.startedAt() == null ? "" : timeFormat.format(s.startedAt()),
                    s.status() == null ? "" : s.status(), request, s.playlistId() == null ? "" : s.playlistId()));
        }
        return rows;
    }

    /** One past session opened: its request, status, plan and result. */
    public List<String> sessionLines(AgentSession s) {
        List<String> lines = new ArrayList<>();
        if (s.request() != null) {
            lines.add(cli("SessionRequest", s.request().rawText()));
        }
        lines.add(cli("SessionStatus", s.status()));
        lines.add(s.planSteps().isEmpty() ? cli("SessionNoPlan") : cli("SessionPlan"));
        for (int i = 0; i < s.planSteps().size(); i++) {
            lines.add("  " + (i + 1) + ". " + s.planSteps().get(i));
        }
        if (s.playlistId() == null) {
            lines.add(cli("SessionNoResult"));
        } else {
            String result = cli("SessionPlaylist", s.playlistId());
            OptionalInt tracks = SessionRecord.trackCount(s.derivedData());
            lines.add(cli("SessionResult", tracks.isPresent() ? result + ", " + cli("SessionTracks", tracks.getAsInt()) : result));
        }
        return lines;
    }

    /**
     * What to tell the user when an action failed, as "Could not ...: reason". {@code actionKey} names the
     * {@code text.gui} wording of the action. Spotify, model and database errors get fixed friendly wording,
     * because their own messages carry HTTP statuses, addresses and response bodies; any other message is kept
     * unless it looks like one of those.
     */
    public String failure(String actionKey, Throwable error) {
        if (error instanceof AuthExpiredException) {
            return config.wording("cliLoginExpired");
        }
        if (error instanceof NegotiationCancelledException) {
            return error.getMessage() == null || error.getMessage().isBlank()
                    ? config.wording("negotiationCancelled") : error.getMessage();
        }
        String reason;
        if (error instanceof RateLimitException) {
            reason = w("RateLimited");
        } else if (error instanceof MusicServiceException) {
            reason = config.wording("playlistCallFailed");
        } else if (error instanceof LLMException) {
            reason = w("LlmFailed");
        } else if (error instanceof PersistenceException) {
            reason = w("DatabaseFailed");
        } else {
            String message = error.getMessage();
            reason = message == null || message.isBlank() || LOOKS_RAW.matcher(message).find() ? w("UnknownError") : message;
        }
        return cli("Failed", w(actionKey), reason);
    }

    /** A creation step shown as progress. */
    public String progress(String stateName) {
        return w("Progress", stateName);
    }

    /** How the run chose its tracks: the strategy, then each constraint it kept. Empty when there is no trace. */
    public List<String> traceLines(DecisionTrace trace) {
        if (trace == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(w("Strategy", trace.strategyName()));
        trace.constraintsSatisfied().forEach(c -> lines.add(w("ConstraintLine", c)));
        return lines;
    }

    /** The score and familiar share of one recommendation. */
    public String recommendationDetail(Recommendation recommendation) {
        return w("RecommendationDetail", recommendation.score(), recommendation.familiarity() * 100);
    }

    /** A playlist's name with its track count and length. */
    public String playlistTitle(Playlist playlist) {
        return w("PlaylistTitle", playlist.name(), playlist.tracks().size(), minutes(playlist.totalDuration()));
    }

    /**
     * The target length typed by the user: blank means 0 (take it from the goal); otherwise a whole number
     * from 0 to the configured maximum. Empty when the text is anything else.
     */
    public java.util.OptionalInt parseMinutes(String text) {
        String typed = text == null ? "" : text.strip();
        if (typed.isEmpty()) {
            return java.util.OptionalInt.of(0);
        }
        if (!typed.chars().allMatch(c -> c >= '0' && c <= '9') || typed.length() > String.valueOf(Integer.MAX_VALUE).length()) {
            return java.util.OptionalInt.empty();
        }
        long value = Long.parseLong(typed);
        return value <= config.parserMaxTargetMinutes() ? java.util.OptionalInt.of((int) value) : java.util.OptionalInt.empty();
    }

    /** A moment as a readable date and time in the formatter's time zone. */
    public String timestamp(java.time.Instant instant) {
        return timeFormat.format(instant);
    }

    /** A saved playlist version as one line of the restore list. */
    public String snapshotLine(SnapshotInfo info) {
        return w("VersionLine", info.name(), info.trackCount(), info.createdAt() == null ? "" : timeFormat.format(info.createdAt()));
    }
}
