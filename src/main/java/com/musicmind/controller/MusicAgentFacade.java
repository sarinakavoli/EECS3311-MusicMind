package com.musicmind.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.musicmind.agent.ConstraintChecker;
import com.musicmind.agent.ExplanationService;
import com.musicmind.agent.NegotiationHandler;
import com.musicmind.agent.Planner;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.PreferenceSettings;
import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.RecommendationEngine;
import com.musicmind.agent.ResponseParser;
import com.musicmind.agent.SearchRanker;
import com.musicmind.agent.TrendComparator;
import com.musicmind.agent.command.PlaylistManager;
import com.musicmind.agent.state.IdleState;
import com.musicmind.agent.state.RunContext;
import com.musicmind.agent.state.RunServices;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.TopItems;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.llm.LLMProvider;
import com.musicmind.memory.MemoryManager;
import com.musicmind.music.MusicService;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TrackList;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Facade (GoF) over the agent subsystem: one high-level method per feature. This
 * class implements the SD03 interaction for F1 (tools fetch the data, the
 * {@link ProfileBuilder} aggregates it, the LLM writes the summary sentence, the
 * {@link MemoryManager} saves the session) and the time-range variant for F2.
 * The GUI and the CLI both go through {@link AppController}, so neither
 * duplicates any of this.
 *
 * F4 follows SD04: the stored preferences and feedback are loaded through the
 * {@link MemoryManager}, the strategy is chosen from the familiar ratio and the
 * {@link RecommendationEngine} builds, filters, scores and explains the candidates.
 * Discovery never calls the LLM and persists nothing.
 *
 * F5 and F11 follow SD01: {@link #createPlaylist} builds an {@link AgentSession} and runs it through its
 * states (planning, retrieving with the feasibility check and negotiation, selecting, validating,
 * executing, done or failed). The phase logic lives in the state classes, not here; this class only wires
 * a run together and keeps the last session for the caller.
 *
 * <p>The LLM only phrases results. With no provider, or when it fails or answers
 * with something unusable, the Stage 1 fallback applies: a sentence computed from
 * the same data, ending with the configured "computed" label.
 */
public class MusicAgentFacade {

    private static final String TOP_ITEMS = "getTopItems";
    private static final String SAVED_TRACKS = "getSavedTracks";
    private static final Gson GSON = new Gson();

    private final ToolManager tools;
    private final MemoryManager memory;
    private final ProfileBuilder profileBuilder;
    private final TrendComparator trendComparator;
    private final PromptBuilder prompts;
    private final LLMProvider llm; // null when no key is configured
    private final Config config;
    private final Planner planner;
    private final ResponseParser parser;
    private final SearchRanker ranker;
    private final RecommendationEngine engine;
    private final Supplier<String> currentUserId;
    private final CreateSupport create;
    private volatile NegotiationHandler negotiationHandler;
    private volatile Consumer<String> progressListener;
    private volatile AgentSession lastSession;
    private volatile String lastSearchNotice;
    private volatile String lastSearchLabel;
    private volatile List<String> lastDiscoverNotices = List.of();
    private volatile DecisionTrace lastDecisionTrace;

    /**
     * What playlist creation needs beyond the read side: the Spotify service and command invoker that write,
     * the explanation service, and the way to sign in again when the token expires ({@code reauthenticator}
     * may be null, in which case an expired token ends the run).
     */
    public record CreateSupport(MusicService music, PlaylistManager playlists, ExplanationService explainer,
                                Runnable reauthenticator) {
    }

    public MusicAgentFacade(ToolManager tools, MemoryManager memory, ProfileBuilder profileBuilder,
                            TrendComparator trendComparator, PromptBuilder prompts, LLMProvider llm,
                            Config config, Planner planner, ResponseParser parser, SearchRanker ranker,
                            RecommendationEngine engine, Supplier<String> currentUserId, CreateSupport create) {
        this.tools = tools;
        this.memory = memory;
        this.profileBuilder = profileBuilder;
        this.trendComparator = trendComparator;
        this.prompts = prompts;
        this.llm = llm;
        this.config = config;
        this.planner = planner;
        this.parser = parser;
        this.ranker = ranker;
        this.engine = engine;
        this.currentUserId = currentUserId;
        this.create = Objects.requireNonNull(create, "create");
    }

    // ---- F1 ----

    /** F1 over the default time range. */
    public ListeningProfile analyzeTaste(User user) {
        return analyzeTaste(user, config.profileDefaultRange());
    }

    /**
     * F1 — always rebuilds. Fetches top artists, top tracks and saved tracks through
     * the tools, builds the profile, adds the summary and saves the session. A read
     * that fails or comes back thin yields a partial profile with a notice; only when
     * every read fails does this throw.
     *
     * @throws IllegalArgumentException if {@code range} is not one of the configured ranges
     * @throws com.musicmind.music.AuthExpiredException if Spotify rejects the login
     */
    public ListeningProfile analyzeTaste(User user, String range) {
        Objects.requireNonNull(user, "user");
        String r = normalizeRange(range);

        Fetch<Artist> artists = fetch(TOP_ITEMS, Map.of("type", "artists", "range", r), "readFailedTopArtists");
        Fetch<Track> topTracks = fetch(TOP_ITEMS, Map.of("type", "tracks", "range", r), "readFailedTopTracks");
        Fetch<Track> saved = fetch(SAVED_TRACKS, Map.of(), "readFailedSavedTracks");

        List<String> errors = Fetch.errors(artists, topTracks, saved);
        List<String> notices = new ArrayList<>(errors);
        if (saved.items() instanceof TrackList list && list.isTruncated()) {
            notices.add(savedTracksCappedNotice());
        }
        if (errors.size() == 3) {
            throw new MusicServiceException(config.wording("profileAllReadsFailed"),
                    Fetch.firstCause(artists, topTracks, saved));
        }
        ListeningProfile built = profileBuilder.build(
                new TopItems(artists.items(), topTracks.items()), saved.items(), notices);
        ListeningProfile profile = withSummary(built);
        profile.setRange(r);

        AgentSession session = new AgentSession(UUID.randomUUID().toString(), Instant.now(), null);
        session.setStatus("done");
        session.setRequest(new AgentRequest("view taste profile (" + r + ")", RequestKind.ANALYZE_TASTE));
        session.setDerivedData(derivedJson(profile, r));
        memory.saveSession(session);
        return profile;
    }

    /** The user's most recent stored profile, rebuilt from its derived data (ids only, no names). */
    public Optional<ListeningProfile> lastProfile(User user) {
        Objects.requireNonNull(user, "user");
        for (AgentSession s : memory.loadHistory(user.id())) {
            if (s.request() != null && s.request().kind() == RequestKind.ANALYZE_TASTE) {
                Optional<ListeningProfile> profile = parseDerived(s.derivedData());
                if (profile.isPresent()) {
                    return profile;
                }
            }
        }
        return Optional.empty();
    }

    // ---- F2 ----

    /**
     * F2 — fetches top artists for each configured range through the tools,
     * compares them deterministically, and has the LLM narrate the result (or
     * falls back to a labelled computed narrative). Not persisted.
     */
    public TrendReport analyzeTrends(User user) {
        Objects.requireNonNull(user, "user");
        Map<String, List<Artist>> byRange = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String range : config.profileRanges()) {
            Fetch<Artist> f = fetch(TOP_ITEMS, Map.of("type", "artists", "range", range), "readFailedTopArtists");
            byRange.put(range, f.items());
            if (f.error() != null) {
                errors.put(range, f.error());
            }
        }
        TrendReport report = trendComparator.compare(byRange, errors);
        long populated = byRange.values().stream().filter(artists -> !artists.isEmpty()).count();
        if (populated < 2) {
            return report.withNarrative("There is not enough data in more than one time range "
                    + "to describe a change in your taste.");
        }
        String narrative = fromLlm(prompts.buildTrendPrompt(report));
        return report.withNarrative(narrative != null ? narrative : fallbackNarrative(report, config.profileRanges()));
    }

    // ---- F3 ----

    /**
     * F3 — the Planner structures the text (or falls back to a raw keyword query), the
     * {@code search} tool fetches the tracks, {@link ResponseParser#verifyTracks} keeps only
     * confirmed ones and the {@link SearchRanker} orders them. An empty result sets
     * {@link #lastSearchNotice()}; a keyword fallback sets {@link #lastSearchLabel()}.
     *
     * @throws IllegalArgumentException if {@code query} is blank
     * @throws MusicServiceException if the search itself fails
     */
    public List<Track> searchMusic(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Type something to search for.");
        }
        lastSearchNotice = null;
        lastSearchLabel = null;
        SearchQuery parsed = planner.parseSearchQuery(query);
        if (parsed.confidence() < config.searchMinConfidence()) {
            lastSearchLabel = config.searchFallbackLabel();
        }
        List<Track> ranked = runSearch(parsed);
        String keywords = parsed.keywords() == null ? "" : parsed.keywords().strip();
        if (ranked.isEmpty() && !keywords.isEmpty() && !keywords.equals(parsed.toSpotifyQuery())) {
            // Genre and filters can empty a search Spotify would answer; try the keywords alone once.
            ranked = runSearch(new SearchQuery(keywords, null, Map.of(), parsed.confidence()));
        }
        if (ranked.isEmpty()) {
            String hint = suggestRelaxedQuery(parsed)
                    .map(q -> String.format(config.searchRelaxedHintTemplate(), q))
                    .orElse(config.searchNoRelaxedHint());
            lastSearchNotice = String.format(config.searchNoResultsTemplate(), query.strip()) + " " + hint;
        }
        return ranked;
    }

    private List<Track> runSearch(SearchQuery query) {
        ToolResult result = tools.execute("search",
                Map.of("query", query.toSpotifyQuery(), "limit", config.searchResultLimit()));
        if (!result.ok()) {
            throw new MusicServiceException(config.wording("searchFailed"), result.cause());
        }
        @SuppressWarnings("unchecked")
        List<Track> results = (List<Track>) result.payload();
        return ranker.rank(query, parser.verifyTracks(results, results));
    }

    /** Why the last search found nothing and what to try instead; null when it found something. */
    public String lastSearchNotice() {
        return lastSearchNotice;
    }

    /** The "plain keyword search" label when the last search could not be interpreted; otherwise null. */
    public String lastSearchLabel() {
        return lastSearchLabel;
    }

    /**
     * A looser version of a query that found nothing: the keywords without the genre and
     * filters, or else the keywords without their last word. Empty when nothing can be dropped.
     */
    public Optional<String> suggestRelaxedQuery(SearchQuery query) {
        String keywords = query.keywords() == null ? "" : query.keywords().strip();
        boolean structured = (query.genre() != null && !query.genre().isBlank()) || !query.filters().isEmpty();
        if (structured) {
            String base = !keywords.isEmpty() ? keywords : query.genre() == null ? "" : query.genre().strip();
            return base.isEmpty() ? Optional.empty() : Optional.of(base);
        }
        int cut = keywords.lastIndexOf(' ');
        return cut < 0 ? Optional.empty() : Optional.of(keywords.substring(0, cut).strip());
    }

    // ---- F4 ----

    /**
     * F4 per SD04. Loads the user's preferences and feedback, applies the preferences to the spec
     * (blocked artists, explicit policy), picks the strategy from the familiar ratio and asks the
     * engine for scored recommendations with reasons. Widening, shortfalls, filtered-out
     * candidates and a database warning are in {@link #lastDiscoverNotices()}.
     *
     * @throws IllegalStateException if nobody is logged in
     * @throws MusicServiceException if every Spotify read failed
     */
    public synchronized List<Recommendation> discover(ListeningProfile profile, PlaylistSpec spec) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(spec, "spec");
        String userId = currentUserId.get();
        if (userId == null) {
            throw new IllegalStateException("Log in before asking for recommendations.");
        }
        lastDiscoverNotices = List.of();
        lastDecisionTrace = null;
        List<String> notices = new ArrayList<>();
        if (profile.notices().contains(savedTracksCappedNotice())) {
            notices.add(savedTracksCappedNotice());
        }

        List<UserPreference> preferences = memory.loadPreferences(userId);
        addWarning(notices);
        List<Feedback> feedback = memory.loadFeedback(userId);
        addWarning(notices);

        PlaylistSpec effective = PreferenceSettings.from(preferences).applyTo(spec);
        engine.chooseStrategy(effective.familiarRatio());
        // F4: exclude tracks the user already knows.
        List<Recommendation> recommendations = engine.recommend(profile, effective, feedback, false);
        notices.addAll(engine.lastNotices());

        lastDiscoverNotices = List.copyOf(notices);
        lastDecisionTrace = engine.lastTrace();
        return recommendations;
    }

    /** What the user should be told about the last discovery: database warning, widening, shortfalls, filters. */
    public List<String> lastDiscoverNotices() {
        return lastDiscoverNotices;
    }

    /** The strategy, reasons and satisfied constraints of the last discovery; null before one. */
    public DecisionTrace lastDecisionTrace() {
        return lastDecisionTrace;
    }

    /** Told in the profile and in discovery when the saved-track read stopped at its cap. */
    private String savedTracksCappedNotice() {
        return String.format(config.wording("savedTracksCapped"), config.spotifyMaxSavedTracks());
    }

    private void addWarning(List<String> notices) {
        String warning = memory.warning();
        if (warning != null && !notices.contains(warning)) {
            notices.add(warning);
        }
    }

    // ---- F5 and F11 ----

    /** Who answers an infeasible request (F11); null means nobody, and such a request then stops with the options listed. */
    public void setNegotiationHandler(NegotiationHandler handler) {
        this.negotiationHandler = handler;
    }

    /** Told the name of each state of a creation run as it starts. */
    public void setProgressListener(Consumer<String> listener) {
        this.progressListener = listener;
    }

    /**
     * F5 per SD01: runs the request through the session states and returns the playlist created on Spotify.
     * The session (with its explanation, notices and decision trace) is {@link #lastSession()} whether the
     * run finished or not. A request that cannot be met, is cancelled or fails ends with the exception that
     * stopped it; nothing is left half-created.
     *
     * @throws IllegalStateException if nobody is logged in, or there is no LLM key to read the request
     * @throws com.musicmind.agent.ClarificationNeededException if the goal is unclear
     * @throws com.musicmind.agent.InfeasibleRequestException if the request cannot be met
     * @throws com.musicmind.agent.NegotiationCancelledException if the user declined every relaxation
     */
    public synchronized Playlist createPlaylist(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        String userId = currentUserId.get();
        if (userId == null) {
            throw new IllegalStateException(config.wording("planNeedsLogin"));
        }
        User user = new User(userId, userId, userId);
        RunServices services = new RunServices(config, planner, new ConstraintChecker(config),
                new PlaylistValidator(), engine, create.playlists(), create.music(), memory,
                create.explainer(), currentUserId, () -> profileFor(user), create.reauthenticator());
        AgentSession session = new AgentSession(UUID.randomUUID().toString(), Instant.now(), new IdleState());
        session.setRequest(request);
        session.setContext(new RunContext(services, request, negotiationHandler, progressListener));
        lastSession = session;
        session.run();
        if (session.failure() != null) {
            throw session.failure();
        }
        return session.context().playlist();
    }

    /** The most recent creation run, finished or failed; null before the first. */
    public AgentSession lastSession() {
        return lastSession;
    }

    /**
     * The profile to plan or discover from: the stored one if it is recent enough, else a fresh build
     * (which stores itself).
     */
    public ListeningProfile profileFor(User user) {
        Instant oldest = Instant.now().minus(Duration.ofHours(config.profileMaxAgeHours()));
        return lastProfile(user)
                .filter(p -> !p.isEmpty() && p.generatedAt() != null && p.generatedAt().isAfter(oldest))
                .orElseGet(() -> analyzeTaste(user));
    }

    // ---- helpers ----

    private String normalizeRange(String range) {
        String r = range == null ? config.profileDefaultRange() : range.trim().toLowerCase(Locale.ROOT);
        if (r.endsWith("_term")) {
            r = r.substring(0, r.length() - "_term".length());
        }
        if (!config.profileRanges().contains(r)) {
            throw new IllegalArgumentException("Unknown time range '" + range + "'. Use one of: "
                    + String.join(", ", config.profileRanges()) + ".");
        }
        return r;
    }

    /** A tool's list payload, or the short wording for why it could not be read (and the original failure). */
    private record Fetch<T>(List<T> items, String error, Throwable cause) {
        static Throwable firstCause(Fetch<?>... fetches) {
            for (Fetch<?> f : fetches) {
                if (f.cause != null) {
                    return f.cause;
                }
            }
            return null;
        }

        static List<String> errors(Fetch<?>... fetches) {
            List<String> out = new ArrayList<>();
            for (Fetch<?> f : fetches) {
                if (f.error != null) {
                    out.add(f.error);
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Fetch<T> fetch(String tool, Map<String, Object> args, String failureWording) {
        ToolResult result = tools.execute(tool, args);
        if (!result.ok()) {
            // The raw error stays on the cause; nothing from it reaches a notice, stored data or a prompt.
            return new Fetch<>(List.of(), config.wording(failureWording), result.cause());
        }
        return new Fetch<>((List<T>) result.payload(), null, null);
    }

    private ListeningProfile withSummary(ListeningProfile p) {
        String summary;
        if (p.isEmpty()) {
            summary = "Not enough listening history yet to describe your taste.";
        } else {
            String fromLlm = fromLlm(prompts.buildSummaryPrompt(p));
            summary = fromLlm != null ? fromLlm : fallbackSummary(p);
        }
        p.setSummary(summary);
        return p;
    }

    /** The LLM's first usable line for a prompt, or null when there is no provider or no usable answer. */
    private String fromLlm(String prompt) {
        if (llm == null) {
            return null;
        }
        try {
            String line = firstLine(llm.complete(prompt));
            return line.isBlank() || line.startsWith("{") ? null : line;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String fallbackSummary(ListeningProfile p) {
        String genres = p.rankedGenres(config.profileFallbackGenres()).stream()
                .map(Map.Entry::getKey).collect(Collectors.joining(", "));
        String text = genres.isEmpty()
                ? "Your top genres could not be worked out from your Spotify data."
                : "Your taste leans toward " + genres + ".";
        return text + " " + config.computedLabel();
    }

    private String fallbackNarrative(TrendReport report, List<String> ranges) {
        String newest = ranges.get(0);
        String oldest = ranges.get(ranges.size() - 1);
        StringBuilder sb = new StringBuilder("Compared with your ").append(oldest).append("-term listening, ")
                .append("your ").append(newest).append("-term listening has ");
        sb.append(report.addedGenres().isEmpty() ? "no newly prominent genres"
                : "newly prominent genres: " + limited(report.addedGenres()));
        sb.append(" and ");
        sb.append(report.droppedGenres().isEmpty() ? "no faded genres"
                : "faded genres: " + limited(report.droppedGenres()));
        sb.append(". ").append(report.addedArtistIds().size()).append(" artists are new and ")
                .append(report.droppedArtistIds().size()).append(" have faded.");
        if (!report.gaps().isEmpty()) {
            sb.append(" Some data was missing or incomplete.");
        }
        return sb.append(' ').append(config.computedLabel()).toString();
    }

    private String limited(List<String> items) {
        return items.stream().limit(config.profileFallbackGenres()).collect(Collectors.joining(", "));
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String raw : text.strip().split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                boolean quoted = line.length() > 1 && line.startsWith("\"") && line.endsWith("\"");
                return quoted ? line.substring(1, line.length() - 1) : line;
            }
        }
        return "";
    }

    /** Only MusicMind's derived data: ids, the genre histogram, notices, the summary. No titles or names. */
    private static String derivedJson(ListeningProfile p, String range) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("range", range);
        derived.put("genreHistogram", p.genreHistogram());
        derived.put("artistIds", p.topArtists().stream().map(Artist::id).toList());
        derived.put("trackIds", p.topTracks().stream().map(Track::id).toList());
        // Genres (from MusicBrainz) and track-artist links are derived data; they let a stored profile score like a fresh one.
        Map<String, List<String>> artistGenres = new LinkedHashMap<>();
        p.topArtists().stream().filter(a -> !a.genres().isEmpty()).forEach(a -> artistGenres.put(a.id(), a.genres()));
        derived.put("artistGenres", artistGenres);
        Map<String, List<String>> trackArtists = new LinkedHashMap<>();
        p.topTracks().stream().filter(t -> !t.artistIds().isEmpty()).forEach(t -> trackArtists.put(t.id(), t.artistIds()));
        derived.put("trackArtistIds", trackArtists);
        derived.put("savedTrackIds", p.savedTrackIds());
        derived.put("summary", p.summary());
        derived.put("partial", p.isPartial());
        derived.put("notices", p.notices());
        derived.put("generatedAt", p.generatedAt() == null ? null : p.generatedAt().toString());
        return GSON.toJson(derived);
    }

    private static Optional<ListeningProfile> parseDerived(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("genreHistogram")) {
                return Optional.empty();
            }
            Map<String, Double> histogram = new LinkedHashMap<>();
            o.getAsJsonObject("genreHistogram").entrySet()
                    .forEach(e -> histogram.put(e.getKey(), e.getValue().getAsDouble()));
            // Profiles stored before genres and track artists were kept lack these maps and load without them.
            Map<String, List<String>> artistGenres = stringLists(o, "artistGenres");
            Map<String, List<String>> trackArtists = stringLists(o, "trackArtistIds");
            List<Artist> artists = strings(o, "artistIds").stream()
                    .map(id -> new Artist(id, "", artistGenres.getOrDefault(id, List.of()))).toList();
            List<Track> tracks = strings(o, "trackIds").stream()
                    .map(id -> new Track(id, "spotify:track:" + id, "", 0, false,
                            trackArtists.getOrDefault(id, List.of()))).toList();
            String summary = o.has("summary") && !o.get("summary").isJsonNull() ? o.get("summary").getAsString() : null;
            Instant at = o.has("generatedAt") && !o.get("generatedAt").isJsonNull()
                    ? Instant.parse(o.get("generatedAt").getAsString()) : null;
            String storedRange = o.has("range") && !o.get("range").isJsonNull() ? o.get("range").getAsString() : null;
            boolean partial = o.has("partial") && o.get("partial").getAsBoolean();
            ListeningProfile stored = new ListeningProfile(histogram, artists, tracks, strings(o, "savedTrackIds"),
                    summary, at, partial, strings(o, "notices"));
            stored.setRange(storedRange);
            return Optional.of(stored);
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException | UnsupportedOperationException
                 | java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Map<String, List<String>> stringLists(JsonObject o, String key) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (o.has(key) && o.get(key).isJsonObject()) {
            o.getAsJsonObject(key).entrySet().forEach(e -> {
                List<String> values = new ArrayList<>();
                e.getValue().getAsJsonArray().forEach(v -> values.add(v.getAsString()));
                out.put(e.getKey(), values);
            });
        }
        return out;
    }

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = o.getAsJsonArray(key);
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }
}
