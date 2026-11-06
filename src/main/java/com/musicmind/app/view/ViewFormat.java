package com.musicmind.app.view;

import com.musicmind.agent.ClarificationNeededException;
import com.musicmind.agent.InfeasibleRequestException;
import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.state.SessionRecord;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSummary;
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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
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

    /** The change column of a {@link TrendRow}: in the newest period only, in the oldest only, or neither. */
    public enum TrendChange { NEW, DROPPED, NONE }

    /**
     * One line of the Trends table. A header row (Genres or Artists) has only a label; an item row has one flag per
     * period, oldest first, saying whether the item is in that period's list, and its change from oldest to newest.
     */
    public record TrendRow(String label, boolean header, List<Boolean> present, TrendChange change, String changeText) { }

    /** The Trends table: the period names (oldest first), the rows, and a note on genre coverage (empty when complete). */
    public record TrendTable(List<String> periods, List<TrendRow> rows, String coverage) { }

    /** One saved version on a timeline line: when it was saved and how the song count moved from the version before. */
    public record VersionLine(SnapshotInfo info, String when, String change) { }

    /** The saved versions of one playlist, newest first. */
    public record VersionGroup(String playlistId, String name, List<VersionLine> versions) { }

    /** A message with a web address, an HTTP status or a JSON body in it is not fit to show. */
    private static final Pattern PROFILE_RANGE = Pattern.compile("\\((short|medium|long)\\)");
    private static final Pattern LOOKS_RAW = Pattern.compile(
            "://|HTTP\\s*\\d{3}|[{}]|com\\.musicmind|Cannot invoke|java\\.|javafx\\.|Exception|\\sat\\s+\\S+\\(");

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
            case "week", "short", "medium", "long" -> w("Range." + range);
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

    /**
     * The playlist list with MusicMind's own song counts in place of Spotify's total while that total is lower:
     * the total (items.total) lags just after a change and showed "0 songs" for a new playlist. A total at or
     * above our count is Spotify's own and is used as it is.
     */
    public List<PlaylistSummary> mergeTrackCounts(List<PlaylistSummary> listed, Map<String, Integer> known) {
        return listed.stream()
                .map(p -> known.containsKey(p.id()) && p.trackCount() < known.get(p.id())
                        ? new PlaylistSummary(p.id(), p.name(), known.get(p.id())) : p)
                .toList();
    }

    /** The own counts still lagging: kept only while Spotify's total is below them, dropped once it reports as many or more. */
    public Map<String, Integer> countsStillLagging(List<PlaylistSummary> listed, Map<String, Integer> known) {
        Map<String, Integer> left = new LinkedHashMap<>();
        for (PlaylistSummary p : listed) {
            Integer own = known.get(p.id());
            if (own != null && p.trackCount() < own) {
                left.put(p.id(), own);
            }
        }
        return left;
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

    /** The line when a song is added again: it is already on the waiting list. */
    public String candidateAlreadyWaiting(String title) {
        return w("CandidateAlready", title);
    }

    /** "N waiting" for the results heading; blank when nothing waits. */
    public String waitingCount(int count) {
        return count <= 0 ? "" : w("WaitingCount", count);
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

    /**
     * The Trends table: one column per range, oldest first (the configured {@code ranges} run newest to oldest), and
     * rows for the top genres then the top artists. An item is listed when it is in the top {@code genresShown} genres
     * or {@code artistsShown} artists of some range, newest range first; a cell is ticked when the item is in that
     * range's data. "New" means in the newest range but not the oldest, "dropped" the reverse. The coverage note says
     * how many of the listed artists have genre data and is empty when all do.
     */
    public TrendTable trendTable(TrendReport report, List<String> ranges, int artistsShown, int genresShown) {
        List<String> oldestFirst = new ArrayList<>(ranges);
        java.util.Collections.reverse(oldestFirst);
        List<String> periods = oldestFirst.stream().map(this::rangeLabel).toList();
        List<TrendRow> rows = new ArrayList<>();

        Map<String, String> genreRows = new LinkedHashMap<>();   // key and label are the genre
        for (String range : ranges) {
            report.genresByRange().getOrDefault(range, Map.of()).keySet().stream().limit(genresShown)
                    .forEach(g -> genreRows.putIfAbsent(g, g));
        }
        if (!genreRows.isEmpty()) {
            rows.add(new TrendRow(w("Genres"), true, List.of(), TrendChange.NONE, ""));
            genreRows.keySet().forEach(g -> rows.add(itemRow(g, oldestFirst,
                    range -> report.genresByRange().getOrDefault(range, Map.of()).containsKey(g))));
        }

        Map<String, String> artistRows = new LinkedHashMap<>();   // id -> name
        for (String range : ranges) {
            report.artistsByRange().getOrDefault(range, List.of()).stream()
                    .filter(a -> a.name() != null && !a.name().isBlank()).limit(artistsShown)
                    .forEach(a -> artistRows.putIfAbsent(a.id(), a.name()));
        }
        if (!artistRows.isEmpty()) {
            rows.add(new TrendRow(w("Artists"), true, List.of(), TrendChange.NONE, ""));
            artistRows.forEach((id, name) -> rows.add(itemRow(name, oldestFirst,
                    range -> report.artistsByRange().getOrDefault(range, List.of()).stream()
                            .anyMatch(a -> id.equals(a.id())))));
        }

        Map<String, Artist> distinct = new LinkedHashMap<>();
        report.artistsByRange().values().forEach(list -> list.forEach(a -> distinct.putIfAbsent(a.id(), a)));
        long known = distinct.values().stream().filter(a -> !a.genres().isEmpty()).count();
        String coverage = known < distinct.size() ? w("TrendCoverage", known, distinct.size()) : "";
        return new TrendTable(periods, rows, coverage);
    }

    private TrendRow itemRow(String label, List<String> oldestFirst, java.util.function.Predicate<String> in) {
        List<Boolean> present = oldestFirst.stream().map(in::test).toList();
        boolean inOldest = !present.isEmpty() && present.get(0);
        boolean inNewest = !present.isEmpty() && present.get(present.size() - 1);
        TrendChange change = inNewest && !inOldest ? TrendChange.NEW
                : inOldest && !inNewest ? TrendChange.DROPPED : TrendChange.NONE;
        String text = switch (change) {
            case NEW -> w("TrendNew");
            case DROPPED -> w("TrendDropped");
            case NONE -> "";
        };
        return new TrendRow(label, false, present, change, text);
    }

    private static List<String> names(TrendReport report, List<String> ids) {
        Map<String, String> byId = new LinkedHashMap<>();
        report.artistsByRange().values().forEach(list -> list.forEach(a -> byId.put(a.id(), a.name())));
        return ids.stream().map(id -> byId.getOrDefault(id, id)).toList();
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
     * because their own messages carry HTTP statuses, addresses and response bodies; a message is kept only from an exception the app throws on purpose
     * (see {@link #deliberateMessage}) and only if it does not look raw; anything else becomes the fixed wording.
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
            reason = !deliberateMessage(error) || message == null || message.isBlank() || LOOKS_RAW.matcher(message).find()
                    ? w("UnknownError") : message;
        }
        return cli("Failed", w(actionKey), reason);
    }

    /**
     * True for the exception types the app throws on purpose with wording written for the user (argument and
     * state checks, an unclear or impossible request, a refused playlist change). Anything else, such as a
     * NullPointerException or ClassCastException, carries a programmer's message that must never reach the screen.
     */
    private static boolean deliberateMessage(Throwable error) {
        return error instanceof IllegalArgumentException || error instanceof IllegalStateException
                || error instanceof ClarificationNeededException || error instanceof InfeasibleRequestException
                || error instanceof PlaylistChangeException;
    }

    private static final java.util.Set<String> RUN_STEPS =
            java.util.Set.of("planning", "retrieving", "selecting", "validating", "executing");

    /** A step of an agent run in plain words ("Finding songs..."); a state with no words of its own is "Working...". */
    public String progress(String stateName) {
        String key = stateName == null ? "" : stateName.strip().toLowerCase(Locale.ROOT);
        return w(RUN_STEPS.contains(key) ? "Progress." + key : "ProgressUnknown");
    }

    /** How many rows of the waiting-songs list show at once; more scroll. */
    public int waitingRowsShown(int count) {
        return Math.max(0, Math.min(count, config.guiWaitingRowsShown()));
    }

    /** How the run chose its tracks: the strategy, then each constraint it kept. Empty when there is no trace. */
    public List<String> traceLines(DecisionTrace trace) {
        if (trace == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(w("Strategy", strategyWords(trace.strategyName())));
        trace.constraintsSatisfied().forEach(c -> lines.add(w("ConstraintLine", c)));
        return lines;
    }

    /**
     * "Mix: 60% new artists, 40% artists you know" for a familiar share (0.4 means 40% known). The share is
     * clamped to 0..1 and the two numbers always add up to 100; an unreadable share counts as the default.
     */
    public String mixLabel(double familiarShare) {
        double share = Double.isNaN(familiarShare) ? config.discoverDefaultFamiliarRatio() : familiarShare;
        long known = Math.round(Math.max(0, Math.min(1, share)) * 100);
        return w("MixLabel", 100 - known, known);
    }

    /** The mix label for the saved familiar share, or for the default share when none is saved. */
    public String mixLabel(java.util.OptionalDouble saved) {
        return mixLabel(saved.orElse(config.discoverDefaultFamiliarRatio()));
    }

    /** "Artist you know" for a familiarity of 1 or more, otherwise "New artist for you". */
    public String familiarityLabel(double familiarity) {
        return w(familiarity >= 1.0 ? "FamiliarKnown" : "FamiliarNew");
    }

    /** A playlist's name with its track count and length. */
    public String playlistTitle(Playlist playlist) {
        return w("PlaylistTitle", playlist.name(), playlist.tracks().size(), minutes(playlist.totalDuration()));
    }

    /**
     * The target length typed as hours and minutes: both blank (or 0) mean 0, take it from the goal. Otherwise
     * whole numbers, minutes 0 to 59, and a total within the planner's shortest and longest playlist.
     *
     * @throws IllegalArgumentException with a message for the user when the text cannot be used
     */
    public int parseHoursMinutes(String hours, String minutes) {
        long h = wholeNumber(hours);
        long m = wholeNumber(minutes);
        if (m > 59) {
            throw new IllegalArgumentException(w("MinutesOver59"));
        }
        long total = h * 60 + m;
        if (total == 0) {
            return 0;
        }
        int min = config.parserMinTargetMinutes();
        int max = config.parserMaxTargetMinutes();
        if (total < min || total > max) {
            throw new IllegalArgumentException(w("LengthRange", min, max));
        }
        return (int) total;
    }

    /** Blank is 0; digits only otherwise (and few enough to fit), else a user-facing refusal. */
    private long wholeNumber(String text) {
        String typed = text == null ? "" : text.strip();
        if (typed.isEmpty()) {
            return 0;
        }
        if (!typed.chars().allMatch(c -> c >= '0' && c <= '9') || typed.length() > 6) {
            throw new IllegalArgumentException(w("LengthNotNumber"));
        }
        return Long.parseLong(typed);
    }

    /**
     * What to tell the user when a block could not be saved. A refused value gets one plain sentence (the
     * underlying message may talk about ids); a refusal because the stored blocks cannot be read keeps its own wording.
     */
    public String blockFailure(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return w("BlockRefused");
        }
        if (error instanceof IllegalStateException && error.getMessage() != null && !error.getMessage().isBlank()
                && !LOOKS_RAW.matcher(error.getMessage()).find()) {
            return error.getMessage();
        }
        return failure("ActionBlock", error);
    }

    /** The artist's name for the blocked list; a missing name, or the id itself, becomes plain words. */
    public String blockedArtistName(String id, String name) {
        return name == null || name.isBlank() || name.strip().equals(id) ? w("BlockedArtistUnknown") : name.strip();
    }

    /** The non-blank parts on one line, separated by " · " (a result line: "Showing songs for the words as typed · Artists found: ..."). */
    public static String joinDetails(String... parts) {
        return java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).map(String::strip)
                .collect(Collectors.joining(" \u00b7 "));
    }

    /** The sentence without its closing full stop, for a part of a one-line result ("Showing songs for the words as typed."). */
    public static String withoutFullStop(String sentence) {
        return sentence != null && sentence.strip().endsWith(".") ? sentence.strip().substring(0, sentence.strip().length() - 1) : sentence;
    }

    /**
     * The text of the one "Why these songs?" expander of a recommendation list: how the run chose (the trace
     * lines, when there are some), then one line per song with the reason it was picked.
     */
    public String whyTheseSongs(List<String> traceLines, List<Recommendation> recommendations) {
        List<String> lines = new ArrayList<>(traceLines);
        if (!lines.isEmpty() && !recommendations.isEmpty()) {
            lines.add("");
        }
        for (Recommendation rec : recommendations) {
            String reason = rec.reason() == null || rec.reason().isBlank() ? w("WhyUnavailable") : rec.reason().strip();
            lines.add(w("ReasonLine", rec.track().title(), reason));
        }
        return String.join("\n", lines);
    }

    /** The first {@code cap} ids, the ones whose titles are looked up; the rest keep the placeholder. */
    public static <T> List<T> firstN(List<T> ids, int cap) {
        return ids.stream().limit(Math.max(0, cap)).toList();
    }

    /** A comma list as saving keeps it: trimmed, lower case, no blanks, no repeats, in the order typed. */
    public static List<String> normalisedList(String text) {
        if (text == null) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(item -> item.strip().toLowerCase(java.util.Locale.ROOT))
                .filter(item -> !item.isEmpty()).distinct().toList();
    }

    /** True when the typed comma list differs from the saved one (order, case, spaces and blank items do not count). */
    public static boolean isChanged(String saved, String typed) {
        return !new java.util.HashSet<>(normalisedList(saved)).equals(new java.util.HashSet<>(normalisedList(typed)));
    }

    /** True when the share the slider shows differs from the saved one at the two decimals that saving keeps. */
    public static boolean shareChanged(double saved, double current) {
        return Math.round(saved * 100) != Math.round(current * 100);
    }

    /** The song's line for the blocked list (live \"Title - Artist\" or a saved title), or plain words when unknown. */
    public String blockedSongName(String title) {
        return title == null || title.isBlank() ? w("BlockedSongUnknown") : title;
    }

    /** "1 of 3" for one of several artists sharing a name; blank when the name is not shared. */
    public String artistPosition(int position, int total) {
        return total <= 1 ? "" : w("ArtistPosition", position, total);
    }

    /** The line above the artist choices: how many artists carry the typed name. */
    public String artistMatchesLine(String typed, int count) {
        if (count <= 0) {
            return w("ArtistMatchesNone", typed);
        }
        return count == 1 ? w("ArtistMatchesOne", typed) : w("ArtistMatchesMany", count, typed);
    }

    /** A moment as a readable date and time in the formatter's time zone. */
    public String timestamp(java.time.Instant instant) {
        return timeFormat.format(instant);
    }

    /** "27 \u2192 22 songs", or "27 songs (no change)" when the count did not change. */
    public String versionChange(int fromCount, int toCount) {
        return fromCount == toCount ? w("VersionSame", toCount) : w("VersionChange", fromCount, toCount);
    }

    /** True for the quick looks (taste profile, trends), which the timeline hides by default. */
    public boolean isRoutine(AgentSession session) {
        return session.request() != null && session.request().kind() == RequestKind.ANALYZE_TASTE;
    }

    /**
     * Saved versions (newest first, as the controller returns them) grouped by playlist, the playlist with the most
     * recent version first. Each line compares its song count with the version saved before it.
     */
    public List<VersionGroup> versionGroups(List<SnapshotInfo> snapshots) {
        Map<String, List<SnapshotInfo>> byPlaylist = new LinkedHashMap<>();
        for (SnapshotInfo info : snapshots) {
            byPlaylist.computeIfAbsent(info.playlistId(), k -> new ArrayList<>()).add(info);
        }
        List<VersionGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<SnapshotInfo>> entry : byPlaylist.entrySet()) {
            List<SnapshotInfo> list = entry.getValue();
            List<VersionLine> lines = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                SnapshotInfo info = list.get(i);
                String change = i + 1 < list.size() ? versionChange(list.get(i + 1).trackCount(), info.trackCount())
                        : w("VersionFirst", info.trackCount());
                lines.add(new VersionLine(info, info.createdAt() == null ? "" : timeFormat.format(info.createdAt()), change));
            }
            groups.add(new VersionGroup(entry.getKey(), list.get(0).name(), lines));
        }
        return groups;
    }

    /** What a timeline entry says: the request, or for a taste profile view the range it looked at. */
    public String timelineText(AgentSession session) {
        if (session.request() == null) {
            return "";
        }
        String raw = session.request().rawText() == null ? "" : session.request().rawText();
        if (isRoutine(session)) {
            Matcher m = PROFILE_RANGE.matcher(raw);
            if (m.find()) {
                return w("TimelineProfile", rangeLabel(m.group(1)));
            }
        }
        return raw;
    }

    /** The name of what the session did ("Create playlist", "Taste profile"); empty when it has no request. */
    public String kindLabel(AgentSession session) {
        return session.request() == null ? "" : config.wording("guiKind." + session.request().kind().name());
    }

    /** The preview heading for a saved version: its name and when it was saved. */
    public String previewTitle(SnapshotInfo info) {
        return w("PreviewTitle", info.name(), info.createdAt() == null ? "" : timeFormat.format(info.createdAt()));
    }

    /** The status to flag on a timeline line: blank when the run finished normally. */
    public String statusFlag(AgentSession session) {
        String status = session.status();
        return status == null || "done".equals(status) ? "" : status;
    }

    /** The line under the previewed songs: how many more the version holds. */
    public String previewMoreLine(int more) {
        return w("PreviewMore", more);
    }

    /** The kinds of activity the History timeline can be narrowed to; OTHER (Discover, search) shows only under ALL. */
    public enum ActivityType { ALL, CREATED, CHANGED, RESTORED, TASTE, OTHER }

    /** The strategy class name in plain words ("BalancedStrategy" becomes a sentence part); never the class name itself. */
    public String strategyWords(String name) {
        String key = name == null ? "" : name.strip().replaceAll("Strategy$", "").toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) {
            return w("StrategyOther");
        }
        try {
            return config.wording("guiStrategy." + key);
        } catch (IllegalStateException unknown) {
            return w("StrategyOther");
        }
    }

    /** What kind of activity a session was; a restore or recreate counts as RESTORED, not as a change or creation. */
    public ActivityType activityType(AgentSession session) {
        if (session.request() == null) {
            return ActivityType.OTHER;
        }
        String text = session.request().rawText() == null ? "" : session.request().rawText();
        boolean restore = text.equals(config.wording("restoreRecordText")) || text.equals(config.wording("recreateRecordText"));
        return switch (session.request().kind()) {
            case ANALYZE_TASTE -> ActivityType.TASTE;
            case CREATE_PLAYLIST, MODIFY_PLAYLIST -> restore ? ActivityType.RESTORED
                    : session.request().kind() == RequestKind.CREATE_PLAYLIST ? ActivityType.CREATED : ActivityType.CHANGED;
            default -> ActivityType.OTHER;
        };
    }

    /**
     * The timeline: only {@code type} (ALL shows everything), sorted by date, newest first or oldest first (a session
     * without a date counts as the oldest). Taste profile views stay hidden unless asked for, either by choosing
     * that type or by {@code showRoutine}.
     */
    public List<AgentSession> activity(List<AgentSession> sessions, ActivityType type, boolean newestFirst, boolean showRoutine) {
        java.util.Comparator<AgentSession> byDate = java.util.Comparator.comparing(AgentSession::startedAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        return sessions.stream()
                .filter(s -> type == ActivityType.ALL ? showRoutine || !isRoutine(s) : activityType(s) == type)
                .sorted(newestFirst ? byDate.reversed() : byDate)
                .toList();
    }

    public String activityTypeLabel(ActivityType type) {
        return w("ActivityType." + type.name());
    }

    /** The found songs that are not already on the waiting list (so an added song leaves the results). */
    public List<Track> withoutWaiting(List<Track> found, List<Track> waiting) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        waiting.forEach(t -> ids.add(t.id()));
        return found.stream().filter(t -> !ids.contains(t.id())).toList();
    }

    /** The song's Spotify web page, or null when the id is missing or not a plain Spotify id. */
    public String spotifyTrackUrl(Track track) {
        if (track == null || track.id() == null || !track.id().matches("[A-Za-z0-9]{1,40}")) {
            return null;
        }
        return w("SpotifyTrackUrl", track.id());
    }
}
