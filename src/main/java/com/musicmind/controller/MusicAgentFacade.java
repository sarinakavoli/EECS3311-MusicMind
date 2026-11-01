package com.musicmind.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.musicmind.agent.ClarificationNeededException;
import com.musicmind.agent.ConstraintChecker;
import com.musicmind.agent.ExplanationService;
import com.musicmind.agent.MoodGenreMap;
import com.musicmind.agent.InfeasibleRequestException;
import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.agent.NegotiationHandler;
import com.musicmind.agent.PlaylistContext;
import com.musicmind.agent.PlaylistEditor;
import com.musicmind.agent.Planner;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.PreferenceSettings;
import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.RecommendationEngine;
import com.musicmind.agent.ResponseParser;
import com.musicmind.agent.SearchRanker;
import com.musicmind.agent.TrendComparator;
import com.musicmind.agent.command.CreatePlaylistCommand;
import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.agent.command.PlaylistCommand;
import com.musicmind.agent.command.PlaylistManager;
import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.agent.state.IdleState;
import com.musicmind.agent.state.RunContext;
import com.musicmind.agent.state.RunServices;
import com.musicmind.agent.state.SessionRecord;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.Relaxation;
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
import com.musicmind.domain.ValidationResult;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.music.AuthExpiredException;
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
import java.util.OptionalInt;
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
    private final PlaylistEditor editor;
    private final PlaylistValidator playlistValidator;
    private volatile String lastChangeSummary = "";
    private volatile boolean lastRestoreChanged;
    private volatile List<String> lastModifyNotices = List.of();
    private volatile String lastValidationNote;

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
        this.editor = new PlaylistEditor(llm, prompts, parser, MoodGenreMap.load(), config);
        this.playlistValidator = new PlaylistValidator(config);
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
        boolean savedCapped = TrackList.isTruncated(saved.items());
        if (savedCapped) {
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
        profile.setSavedCapped(savedCapped);

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
        if (narrative == null) {
            return report.withNarrative(fallbackNarrative(report, config.profileRanges()));
        }
        if (report.genreCoveragePartial(config.trendMinGenreCoverage()) && !narrative.toLowerCase().contains("partial")) {
            // The model was told to say coverage is partial; if it did not, the user still must be told.
            narrative += " " + String.format(config.wording("trendCoveragePartial"),
                    report.coverageSummary(config.profileRanges()));
        }
        return report.withNarrative(narrative);
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
        if (profile.isSavedCapped()) {
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

    // ---- F6 and F7: modify, validate, undo ----

    /**
     * F6 per SD02: reads the playlist, has the {@link PlaylistEditor} turn the instruction into commands,
     * saves a snapshot and applies the commands as one undoable step through {@link PlaylistManager}, then
     * re-validates and records the change in the history. The summary of what changed is
     * {@link #lastChangeSummary()}; an approximate mood edit and any constraint the result now breaks are in
     * {@link #lastModifyNotices()}. Nothing is changed if the instruction cannot be understood.
     *
     * @throws IllegalArgumentException if the playlist id or the instruction is blank
     * @throws IllegalStateException if nobody is logged in, or there is no LLM key
     * @throws com.musicmind.agent.ClarificationNeededException if the instruction is ambiguous or unclear
     * @throws PlaylistChangeException if the playlist cannot be read or changed (permission, missing, snapshot)
     */
    public synchronized Playlist modifyPlaylist(String playlistId, String instruction) {
        String id = requirePlaylistId(playlistId);
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException(config.wording("modifyBlankInstruction"));
        }
        editor.requireLlm();   // before anything is read from Spotify
        String userId = currentUserId.get();
        if (userId == null) {
            throw new IllegalStateException(config.wording("modifyNeedsLogin"));
        }
        lastChangeSummary = "";
        lastModifyNotices = List.of();
        List<Track> items = readItems(id);
        requireComplete(items);
        Playlist current = named(id, items);
        PlaylistEditor.EditPlan plan;
        try {
            plan = editor.plan(new PlaylistContext(id, current.tracks(), create.music()), instruction.strip(),
                    knownArtistGenres(userId));
        } catch (MusicServiceException e) {
            throw playlistAccess(e);
        }
        List<String> notices = new ArrayList<>();
        PlaylistSpec stored = specFor(userId, id).spec();
        plan = replanWhileViolating(current, plan, instruction.strip(), stored, userId);
        plan = negotiateEdit(current, plan, notices, stored);
        int snapshotId;
        try {
            snapshotId = create.playlists().modify(current, plan.commands());
        } catch (MusicServiceException e) {
            throw playlistAccess(e);
        }
        int target = targetAfter(stored, plan, current);
        PlaylistSpec judged = withTarget(stored, target);

        Playlist updated = loadPlaylist(id);
        if (plan.approximate()) {
            notices.add(String.format(config.wording("modifyApproximate"), plan.mood(),
                    String.join(", ", MoodGenreMap.load().genresFor(plan.mood()))));
        }
        ValidationResult result = validate(updated, judged);
        if (!result.passed()) {
            notices.add(String.format(config.wording("modifyViolations"), String.join("; ", result.violations())));
        }
        lastChangeSummary = plan.commands().stream().map(PlaylistCommand::describe)
                .collect(Collectors.joining(config.wording("cmdBatchSeparator") + " "));
        record(userId, id, instruction.strip(), plan,
                PlaylistTargets.change(target, stored.targetMinutes(), snapshotId));
        addWarning(notices);
        lastModifyNotices = List.copyOf(notices);
        return updated;
    }

    /** The spec an edit is judged by: the playlist's rules without its old duration target, which the edit's own intent replaces. */
    private static PlaylistSpec editSpec(PlaylistSpec spec) {
        return new PlaylistSpec(0, spec.allowExplicit(), spec.familiarRatio(), spec.blockedArtists(), spec.name());
    }

    private static PlaylistSpec withTarget(PlaylistSpec spec, int targetMinutes) {
        return new PlaylistSpec(targetMinutes, spec.allowExplicit(), spec.familiarRatio(), spec.blockedArtists(), spec.name());
    }

    /**
     * The playlist's target length after the edit. An edit that states a length (a change such as "15 minutes
     * shorter", or a new total) moves it: the stored target (or, with none, the playlist's current length) plus
     * the change. An edit that does not state one, or states one outside the allowed range, leaves it as it was.
     */
    private int targetAfter(PlaylistSpec stored, PlaylistEditor.EditPlan plan, Playlist before) {
        if (plan.targetMinutes() > 0) {
            return plan.targetMinutes();
        }
        if (plan.lengthChangeMinutes() == 0) {
            return stored.targetMinutes();
        }
        int base = stored.targetMinutes() > 0 ? stored.targetMinutes()
                : (int) Math.round(before.totalDuration() / 60_000.0);
        long moved = (long) base + plan.lengthChangeMinutes();
        return moved >= config.parserMinTargetMinutes() && moved <= config.parserMaxTargetMinutes()
                ? (int) moved : stored.targetMinutes();
    }

    /**
     * The F6 adjustment loop that F7 drives: while the simulated result of the edit introduces a violation,
     * plan again with the violations stated, at most {@code plan.maxValidationRetries} times. Nothing is
     * written. It stops early, keeping the current plan, when the LLM fails or cannot produce a plan, or when
     * the new plan is the same as the previous one (asking again would only repeat). What still fails afterwards
     * goes to {@link #negotiateEdit}.
     */
    private PlaylistEditor.EditPlan replanWhileViolating(Playlist current, PlaylistEditor.EditPlan plan,
                                                         String instruction, PlaylistSpec editSpec, String userId) {
        ConstraintChecker checker = new ConstraintChecker(config);
        for (int attempt = 0; attempt < config.planMaxValidationRetries(); attempt++) {
            List<Track> after = current.tracks();
            for (PlaylistCommand command : plan.commands()) {
                after = command.simulate(after);
            }
            List<String> introduced = checker.introduced(current.tracks(), after, editSpec);
            if (introduced.isEmpty()) {
                return plan;
            }
            String hint = String.format(config.wording("modifyReplanHint"), String.join("; ", introduced));
            PlaylistEditor.EditPlan next;
            try {
                next = editor.plan(new PlaylistContext(current.spotifyId(), current.tracks(), create.music()),
                        instruction + "\n" + hint, knownArtistGenres(userId));
            } catch (MusicServiceException e) {
                throw playlistAccess(e);
            } catch (LLMException | ClarificationNeededException e) {
                return plan;   // the plan already made is still a valid one to negotiate over
            }
            if (describe(next).equals(describe(plan))) {
                return plan;
            }
            plan = next.keepingLengthOf(plan);   // a re-plan that leaves the length out still means it
        }
        return plan;
    }

    private static List<String> describe(PlaylistEditor.EditPlan plan) {
        return plan.commands().stream().map(PlaylistCommand::describe).toList();
    }

    /**
     * F11 during modify: if the edit would leave the playlist outside its length limits, ask the handler
     * (set with {@link #setNegotiationHandler}) which smaller edit to apply. Everything here happens before
     * the first Spotify write, so declining or failing leaves the playlist untouched.
     */
    private PlaylistEditor.EditPlan negotiateEdit(Playlist current, PlaylistEditor.EditPlan plan, List<String> notices,
                                                  PlaylistSpec editSpec) {
        FeasibilityReport report = new ConstraintChecker(config).checkEdit(current.tracks(), plan.commands(), editSpec);
        if (report.feasible()) {
            return plan;
        }
        String conflicts = String.join(" ", report.conflicts());
        if (report.relaxations().isEmpty()) {
            throw new InfeasibleRequestException(conflicts + " " + config.wording("modifyNone"));
        }
        NegotiationHandler handler = negotiationHandler;
        if (handler == null) {
            throw new InfeasibleRequestException(conflicts + " " + String.format(
                    config.wording("modifyNoHandler"), String.join("; ", report.relaxationOptions())));
        }
        Optional<Relaxation> choice = handler.choose(report, planner.phraseOptions(report));
        if (choice.isEmpty()) {
            throw new NegotiationCancelledException(config.wording("modifyCancelled"));
        }
        Relaxation chosen = choice.get();
        if (!report.relaxations().contains(chosen)) {
            throw new IllegalArgumentException(config.wording("modifyBadChoice"));
        }
        notices.add(String.format(config.wording("modifyAdjusted"), chosen.description()));
        return plan.withCommands(plan.commands().subList(0, (int) chosen.value()));
    }

    /** What the last {@link #modifyPlaylist} changed, in words; empty before one or when nothing was changed. */
    public String lastChangeSummary() {
        return lastChangeSummary;
    }

    /** The approximate-edit label, constraints the result breaks and any database warning for the last modify. */
    public List<String> lastModifyNotices() {
        return lastModifyNotices;
    }

    /**
     * Reverts the most recent change to the playlist: the last applied command of this run, else the latest
     * saved snapshot not yet used by an undo.
     *
     * @throws PlaylistChangeException if there is nothing to undo, the history is unavailable or the playlist cannot be changed
     */
    public synchronized void undo(String playlistId) {
        String id = requirePlaylistId(playlistId);
        try {
            create.playlists().undo(id);
        } catch (MusicServiceException e) {
            throw playlistAccess(e);
        }
        String userId = currentUserId.get();
        if (userId != null) {
            targets(userId, id).lastUnreverted().ifPresent(change ->
                    saveChangeSession(id, config.wording("undoRecordText"),
                            PlaylistTargets.revert(change.before(), change.sessionId())));
        }
    }

    /** F7 on a track list: the deterministic rule set of {@link PlaylistValidator}. */
    public ValidationResult validate(Playlist playlist, PlaylistSpec spec) {
        return playlistValidator.validate(playlist.tracks(), spec);
    }

    /**
     * F7 on a playlist on Spotify: the spec saved with the session that created it, else one built from the
     * stored preferences ({@link #lastValidationNote()} says which).
     *
     * @throws PlaylistChangeException if the playlist cannot be read
     */
    public ValidationResult validatePlaylist(String playlistId) {
        String id = requirePlaylistId(playlistId);
        String userId = currentUserId.get();
        if (userId == null) {
            throw new IllegalStateException(config.wording("modifyNeedsLogin"));
        }
        List<Track> items = readItems(id);
        Playlist playlist = named(id, items);
        SpecChoice choice = specFor(userId, id);
        String note = config.wording(choice.fromSession() ? "validateAgainstSession" : "validateAgainstPreferences");
        if (TrackList.isTruncated(items)) {
            note += " " + String.format(config.wording("validatePartial"), items.size());
        }
        lastValidationNote = memory.warning() == null ? note : note + " " + memory.warning();
        return validate(playlist, choice.spec());
    }

    /** Which constraints the last {@link #validatePlaylist} used; null before one. */
    public String lastValidationNote() {
        return lastValidationNote;
    }

    private record SpecChoice(PlaylistSpec spec, boolean fromSession) { }

    /**
     * The constraints a playlist is judged by: the spec saved with the session that created it, else one built
     * from the stored preferences. Either way the target length is the one the latest edit, undo or restore
     * left ({@link PlaylistTargets}), not necessarily the creation-time one.
     */
    private SpecChoice specFor(String userId, String playlistId) {
        AgentSession created = memory.findSessionByPlaylist(playlistId);
        Optional<PlaylistSpec> saved = created == null ? Optional.empty() : SessionRecord.specOf(created.derivedData());
        SpecChoice choice;
        if (saved.isPresent()) {
            choice = new SpecChoice(saved.get(), true);
        } else {
            PlaylistSpec base = new PlaylistSpec(0, true, config.discoverDefaultFamiliarRatio(), List.of(), null);
            choice = new SpecChoice(PreferenceSettings.from(memory.loadPreferences(userId)).applyTo(base), false);
        }
        OptionalInt target = targets(userId, playlistId).current();
        return target.isPresent() ? new SpecChoice(withTarget(choice.spec(), target.getAsInt()), choice.fromSession()) : choice;
    }

    private PlaylistTargets targets(String userId, String playlistId) {
        return new PlaylistTargets(memory.loadHistory(userId), playlistId);
    }

    /** The playlist as Spotify holds it, named after the spec saved when it was created. */
    private Playlist loadPlaylist(String id) {
        return named(id, readItems(id));
    }

    private List<Track> readItems(String id) {
        try {
            return create.music().getPlaylistItems(id);
        } catch (MusicServiceException e) {
            throw playlistAccess(e);
        }
    }

    /** Edits, undo and restore must see the whole playlist; a read cut short would corrupt the change. */
    private void requireComplete(List<Track> items) {
        if (TrackList.isTruncated(items)) {
            throw new PlaylistChangeException(String.format(config.wording("playlistTooLong"), config.spotifyMaxItems()));
        }
    }

    private Playlist named(String id, List<Track> tracks) {
        AgentSession created = memory.findSessionByPlaylist(id);
        String name = created == null ? null : SessionRecord.specOf(created.derivedData())
                .map(PlaylistSpec::name).orElse(null);
        return new Playlist(id, name == null || name.isBlank() ? config.wording("playlistUnnamed") : name, tracks);
    }

    /** Genres known per artist id from the stored taste profile (no Spotify call); empty if none is stored. */
    private Map<String, List<String>> knownArtistGenres(String userId) {
        Map<String, List<String>> genres = new LinkedHashMap<>();
        lastProfile(new User(userId, userId, userId)).ifPresent(p -> p.topArtists().stream()
                .filter(a -> !a.genres().isEmpty()).forEach(a -> genres.put(a.id(), a.genres())));
        return genres;
    }

    /** Stores the edit in the history: the user's words, the playlist id and MusicMind's own flags, no Spotify content. */
    private void record(String userId, String playlistId, String instruction, PlaylistEditor.EditPlan plan,
                        Map<String, Object> targetChange) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("approximate", plan.approximate());
        derived.put("mood", plan.mood());
        derived.put("commands", plan.commands().size());
        derived.putAll(targetChange);
        saveChangeSession(playlistId, instruction, derived);
    }

    /** A MODIFY_PLAYLIST session for the history: the words, the playlist id and MusicMind's own numbers. */
    private void saveChangeSession(String playlistId, String text, Map<String, Object> derived) {
        AgentSession session = new AgentSession(UUID.randomUUID().toString(), Instant.now(), null);
        session.setStatus("done");
        session.setRequest(new AgentRequest(text, RequestKind.MODIFY_PLAYLIST));
        session.setPlaylistId(playlistId);
        session.setDerivedData(GSON.toJson(derived));
        memory.saveSession(session);
    }

    private String requirePlaylistId(String playlistId) {
        String id = playlistId == null ? "" : playlistId.strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException(config.wording("playlistIdBlank"));
        }
        return id;
    }

    /**
     * A Spotify failure on a playlist as a short message: no permission (only owned or collaborative
     * playlists can be read), not found, or a general failure. Authentication failures pass through so the
     * caller can sign in again. The original exception stays as the cause.
     */
    private RuntimeException playlistAccess(MusicServiceException e) {
        if (e instanceof AuthExpiredException) {
            return e;
        }
        String key = switch (e.statusCode()) {
            case 403 -> "playlistNoPermission";
            case 404 -> "playlistNotFound";
            default -> "playlistCallFailed";
        };
        return new PlaylistChangeException(config.wording(key), e);
    }

    // ---- F10 ----

    /** F10 — the explanation of a session's choices, from its recorded decision trace only. */
    public String explain(AgentSession session) {
        return create.explainer().explain(session);
    }

    // ---- F12 ----

    /**
     * Puts a playlist back to a saved version. The playlist's current content is saved first, so the restore
     * can itself be undone.
     *
     * @throws PlaylistMissingException if the Spotify playlist of the snapshot no longer exists
     * @throws PlaylistChangeException if the snapshot is unknown or unavailable, or the playlist cannot be changed
     */
    public synchronized Playlist restore(int snapshotId) {
        lastRestoreChanged = false;
        Playlist snapshot = loadSnapshot(snapshotId);
        String id = snapshot.spotifyId();
        List<Track> now;
        try {
            now = create.music().getPlaylistItems(id);
        } catch (MusicServiceException e) {
            if (e.statusCode() == 404) {
                throw new PlaylistMissingException(snapshotId,
                        String.format(config.wording("restoreMissing"), snapshotId), e);
            }
            throw playlistAccess(e);
        }
        requireComplete(now);
        if (now.stream().map(Track::uri).toList().equals(snapshot.tracks().stream().map(Track::uri).toList())) {
            return named(id, now);   // already identical: nothing to save or change
        }
        int saved;
        try {
            saved = create.playlists().restoreFrom(new Playlist(id, snapshot.name(), now), snapshotId);
        } catch (MusicServiceException e) {
            throw playlistAccess(e);
        }
        lastRestoreChanged = true;
        String userId = currentUserId.get();
        if (userId != null) {
            int current = specFor(userId, id).spec().targetMinutes();
            int back = targets(userId, id).targetAt(snapshotId).orElse(current);
            saveChangeSession(id, config.wording("restoreRecordText"), PlaylistTargets.change(back, current, saved));
        }
        return loadPlaylist(id);
    }

    /**
     * Creates a new playlist from a snapshot whose Spotify playlist was deleted, with the saved name and
     * tracks, through the same {@link CreatePlaylistCommand} F5 uses.
     */
    public synchronized Playlist recreateFromSnapshot(int snapshotId) {
        Playlist snapshot = loadSnapshot(snapshotId);
        String userId = currentUserId.get();
        if (userId == null) {
            throw new IllegalStateException(config.wording("modifyNeedsLogin"));
        }
        CreatePlaylistCommand command = new CreatePlaylistCommand(config, create.music(), userId, snapshot.name(),
                snapshot.tracks().stream().map(Track::uri).toList());
        try {
            create.playlists().execute(command);
        } catch (MusicServiceException e) {
            throw playlistAccess(e);
        }
        return loadPlaylist(command.playlistId());
    }

    /** Whether the last {@link #restore} changed the playlist; false when it already matched the saved version. */
    public boolean lastRestoreChanged() {
        return lastRestoreChanged;
    }

    private Playlist loadSnapshot(int snapshotId) {
        Playlist snapshot;
        try {
            snapshot = memory.loadSnapshot(snapshotId);
        } catch (PersistenceException e) {
            throw new PlaylistChangeException(config.wording("snapshotUnavailable"), e);
        }
        if (snapshot == null) {
            throw new PlaylistChangeException(String.format(config.wording("restoreUnknown"), snapshotId));
        }
        return snapshot;
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
        boolean partial = report.genreCoveragePartial(config.trendMinGenreCoverage());
        StringBuilder sb = new StringBuilder();
        if (partial) {
            // Genre data is missing for many artists, so a genre "change" may be missing data: say so, claim nothing.
            sb.append(String.format(config.wording("trendCoveragePartial"), report.coverageSummary(config.profileRanges()))).append(' ');
            sb.append("Genres found only in your ").append(newest).append("-term data: ")
                    .append(report.addedGenres().isEmpty() ? "none" : limited(report.addedGenres()))
                    .append("; only in your ").append(oldest).append("-term data: ")
                    .append(report.droppedGenres().isEmpty() ? "none" : limited(report.droppedGenres())).append('.');
        } else {
            sb.append("Compared with your ").append(oldest).append("-term listening, ")
                    .append("your ").append(newest).append("-term listening has ");
            sb.append(report.addedGenres().isEmpty() ? "no newly prominent genres"
                    : "newly prominent genres: " + limited(report.addedGenres()));
            sb.append(" and ");
            sb.append(report.droppedGenres().isEmpty() ? "no faded genres"
                    : "faded genres: " + limited(report.droppedGenres()));
            sb.append('.');
        }
        sb.append(' ').append(report.addedArtistIds().size()).append(" artists are new and ")
                .append(report.droppedArtistIds().size()).append(" have faded.");
        if (!report.gaps().isEmpty() && !partial) {
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
        derived.put("savedCapped", p.isSavedCapped());
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
            // Profiles stored before the flag existed carry none and load as not cut short.
            stored.setSavedCapped(o.has("savedCapped") && o.get("savedCapped").getAsBoolean());
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
