package com.musicmind.controller;

import com.musicmind.agent.ExplanationService;
import com.musicmind.agent.NegotiationHandler;
import com.musicmind.agent.Planner;
import com.musicmind.agent.PreferenceSettings;
import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.RecommendationEngine;
import com.musicmind.agent.ResponseParser;
import com.musicmind.agent.SearchRanker;
import com.musicmind.agent.TrendComparator;
import com.musicmind.agent.command.PlaylistManager;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.Verdict;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.OpenAiProvider;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.GenreRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.MusicService;
import com.musicmind.music.GenreEnrichingMusicService;
import com.musicmind.music.MusicBrainzAdapter;
import com.musicmind.music.OAuthTokenStore;
import com.musicmind.music.RetryingMusicService;
import com.musicmind.music.SpotifyAdapter;
import com.musicmind.tool.MemoryTool;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application controller (MVC): the single wiring point shared by the GUI and
 * the CLI. It assembles the dependency graph (real Spotify and OpenAI when
 * configured, otherwise graceful fallbacks) and exposes the features as plain
 * method calls that return domain objects for a view to render. It holds no
 * business logic of its own.
 */
public class AppController {

    /** The user id stamped on stored data when Spotify is not configured. */
    private static final String LOCAL_USER = "local";
    private static final String TRACK_URI_PREFIX = "spotify:track:";

    private final MusicService music; // null until Spotify is configured
    private final MemoryManager memory;
    private final MusicAgentFacade facade; // null when music is null
    private final boolean llmConfigured;
    private final Config config;
    private volatile User currentUser;

    /**
     * @param music  the Spotify service, or null when Spotify is not configured
     * @param llm    the LLM, or null when no key is configured (F1 and F2 then use their labelled fallbacks)
     */
    public AppController(MusicService music, MemoryManager memory, LLMProvider llm, Config config) {
        this(music, memory, llm, config, null);
    }

    /**
     * @param reauthenticator signs the user in to Spotify again when a playlist run finds the token expired
     *                        (null: an expired token then ends the run)
     */
    public AppController(MusicService music, MemoryManager memory, LLMProvider llm, Config config,
                         Runnable reauthenticator) {
        this.music = music;
        this.memory = memory;
        this.llmConfigured = llm != null;
        this.config = config;
        if (music == null) {
            this.facade = null;
        } else {
            ToolManager tools = new ToolManager();
            SpotifyTool.allFor(music).forEach(tools::register);
            tools.register(new MemoryTool(memory));
            ProfileBuilder profileBuilder = new ProfileBuilder(config);
            PromptBuilder prompts = new PromptBuilder(config);
            ResponseParser parser = new ResponseParser(config);
            this.facade = new MusicAgentFacade(tools, memory, profileBuilder,
                    new TrendComparator(profileBuilder, config), prompts, llm, config,
                    new Planner(llm, prompts, parser, config), parser, new SearchRanker(config),
                    new RecommendationEngine(tools, config),
                    () -> currentUser == null ? null : currentUser.id(),
                    new MusicAgentFacade.CreateSupport(music, new PlaylistManager(config, music, memory),
                            new ExplanationService(llm, prompts, parser, config), reauthenticator));
        }
    }

    /** Build a controller from the local {@code .env} configuration. */
    public static AppController create() {
        Config config = Config.load();

        // No key means no provider (R17): features with a documented fallback label it.
        LLMProvider llm = config.llmConfigured()
                ? new OpenAiProvider(config.llmApiKey(), config.llmModel())
                : null;

        // The database may be down: the app keeps running and MemoryManager warns (F8).
        Database db = new Database(config.databaseUrl(), config.databaseUser(), config.databasePassword());
        try {
            db.init();
        } catch (PersistenceException ignored) {
            // MemoryManager falls back to safe defaults and exposes the warning
        }

        // Repositories stamp the Spotify user id on writes. The adapter caches it after the first
        // GET /me, so once login() has run this costs nothing; without Spotify nothing is written under a name.
        MusicService music = null;
        Runnable reauthenticator = null;
        Supplier<String> userId = () -> LOCAL_USER;
        if (config.spotifyConfigured()) {
            // Spotify returns no artist genres to this app, so MusicBrainz supplies them (R18).
            OAuthTokenStore auth = new OAuthTokenStore(config.spotifyClientId(), config.spotifyRedirectUri());
            music = new GenreEnrichingMusicService(new RetryingMusicService(new SpotifyAdapter(auth)),
                    new MusicBrainzAdapter(config), new GenreRepository(db), config);
            userId = music::currentUserId;
            reauthenticator = auth::login;
        }
        MemoryManager memory = new MemoryManager(db,
                new PreferenceRepository(db, userId),
                new FeedbackRepository(db, userId),
                new SessionRepository(db, userId),
                new PlaylistHistoryRepository(db, userId));
        return new AppController(music, memory, llm, config, reauthenticator);
    }

    public boolean isSpotifyConfigured() {
        return music != null;
    }

    public boolean isLlmConfigured() {
        return llmConfigured;
    }

    public MemoryManager memory() {
        return memory;
    }

    /** The line both interfaces show to credit Spotify for the data. */
    public String spotifyAttribution() {
        return config.spotifyAttribution();
    }

    /** The line both interfaces show to credit MusicBrainz for the genres. */
    public String genreAttribution() {
        return config.genreAttribution();
    }

    /** The time ranges the views offer, most recent first. */
    public List<String> timeRanges() {
        return config.profileRanges();
    }

    // ---- UC01 ----

    /** Signs in to Spotify (the first call opens the browser) and returns the user. */
    public User login() {
        requireSpotify();
        String id = music.currentUserId();
        currentUser = new User(id, id, id);
        return currentUser;
    }

    /** The user from {@link #login()}, or null if nobody has logged in. */
    public User currentUser() {
        return currentUser;
    }

    // ---- F1 ----

    /** F1 — always rebuilds the profile over the default time range. */
    public ListeningProfile analyzeTaste(User user) {
        requireSpotify();
        return facade.analyzeTaste(user);
    }

    /** F1 over a chosen time range (short, medium or long). */
    public ListeningProfile analyzeTaste(User user, String range) {
        requireSpotify();
        return facade.analyzeTaste(user, range);
    }

    /** The latest stored profile for the user, if one has been built before. */
    public Optional<ListeningProfile> lastProfile(User user) {
        requireSpotify();
        return facade.lastProfile(user);
    }

    // ---- F2 ----

    public TrendReport analyzeTrends(User user) {
        requireSpotify();
        return facade.analyzeTrends(user);
    }

    // ---- F3 ----

    /** F3 — ranked tracks for a free-text query. */
    public List<Track> searchMusic(String query) {
        requireSpotify();
        return facade.searchMusic(query);
    }

    /** Why the last search found nothing and what to try; null when it found results. */
    public String lastSearchNotice() {
        return facade == null ? null : facade.lastSearchNotice();
    }

    /** The "plain keyword search" label if the last search was not interpreted; otherwise null. */
    public String lastSearchLabel() {
        return facade == null ? null : facade.lastSearchLabel();
    }

    // ---- F4 ----

    /**
     * A discovery spec: the familiar share is {@code 1 - novelty} when a novelty is given, else the
     * stored familiar-bias preference, else the configured default.
     *
     * @param novelty 0..1, or null to use the stored preference
     * @throws IllegalArgumentException if novelty is outside 0..1
     */
    public PlaylistSpec discoverSpec(Double novelty) {
        double ratio;
        if (novelty != null) {
            if (Double.isNaN(novelty) || novelty < 0 || novelty > 1) {
                throw new IllegalArgumentException("Novelty must be between 0 and 1.");
            }
            ratio = 1 - novelty;
        } else {
            ratio = PreferenceSettings.from(memory.loadPreferences(userId())).familiarBias()
                    .orElse(config.discoverDefaultFamiliarRatio());
        }
        return new PlaylistSpec(0, true, ratio, List.of(), null);
    }

    /**
     * F4 — recommendations for the logged-in user (signing in if needed). The taste profile is the
     * stored one, or is built when there is none yet or the stored one is older than
     * {@code profile.maxAgeHours}. Discovery itself persists nothing.
     */
    public List<Recommendation> discover(PlaylistSpec spec) {
        requireSpotify();
        User user = currentUser != null ? currentUser : login();
        return facade.discover(facade.profileFor(user), spec);
    }

    /** What the user should be told about the last discovery (database warning, widening, filters); never null. */
    public List<String> lastDiscoverNotices() {
        return facade == null ? List.of() : facade.lastDiscoverNotices();
    }

    /** Why the last discovery chose what it did; null before one. */
    public DecisionTrace lastDecisionTrace() {
        return facade == null ? null : facade.lastDecisionTrace();
    }

    // ---- F5 and F11 ----

    /**
     * F5 — creates a playlist on Spotify from a goal (signing in if needed). Progress goes to the listener
     * set with {@link #setProgressListener}; an infeasible request is put to the handler set with
     * {@link #setNegotiationHandler}. The finished session, with its explanation, is {@link #lastSession()}.
     *
     * @throws IllegalStateException if there is no LLM key to read the request
     * @throws com.musicmind.agent.ClarificationNeededException if the goal is unclear
     * @throws com.musicmind.agent.InfeasibleRequestException if the request cannot be met
     * @throws com.musicmind.agent.NegotiationCancelledException if the user declined every option
     */
    public Playlist createPlaylist(AgentRequest request) {
        requireSpotify();
        if (currentUser == null) {
            login();
        }
        return facade.createPlaylist(request);
    }

    /** The most recent creation run (id, status, notices, explanation, trace), finished or failed; null before one. */
    public AgentSession lastSession() {
        return facade == null ? null : facade.lastSession();
    }

    /** Who is asked to choose a relaxation when a request cannot be met as stated (F11); null: nobody. */
    public void setNegotiationHandler(NegotiationHandler handler) {
        requireSpotify();
        facade.setNegotiationHandler(handler);
    }

    /** Receives the name of each state of a creation run as it starts. */
    public void setProgressListener(Consumer<String> listener) {
        requireSpotify();
        facade.setProgressListener(listener);
    }

    // ---- F8 ----

    /**
     * Turns what a user typed into a preference.
     *
     * @throws IllegalArgumentException with a message fit to show, for an unknown key or unusable value
     */
    public UserPreference parsePreference(String key, String value) {
        return PreferenceSettings.parse(key, value, config);
    }

    /**
     * Stores a preference (an existing key is updated). With the database down the app keeps going and
     * {@code memory().warning()} says it was not saved.
     *
     * @throws IllegalArgumentException for a blank key or a familiar bias outside 0..1
     */
    public void setPreference(UserPreference pref) {
        memory.savePreference(pref);
    }

    /** The user's stored preferences; the safe defaults (and a warning) while the database is down. */
    public List<UserPreference> getPreferences() {
        return memory.loadPreferences(userId());
    }

    // ---- F9 ----

    /**
     * Records an accept or reject verdict on a track (a Spotify track URI is accepted too). A repeat
     * verdict on the same track replaces the earlier one.
     *
     * @throws IllegalArgumentException if the track id is blank or the verdict is missing
     */
    public void recordFeedback(String trackId, Verdict verdict) {
        if (trackId == null || trackId.isBlank()) {
            throw new IllegalArgumentException("Give the id of the track.");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("Choose accept or reject.");
        }
        String id = trackId.strip();
        if (id.startsWith(TRACK_URI_PREFIX)) {
            id = id.substring(TRACK_URI_PREFIX.length());
        }
        memory.saveFeedback(new Feedback(id, verdict, Instant.now()));
    }

    /** The id data is stored under: the logged-in user (signing in if needed), or the local user without Spotify. */
    private String userId() {
        if (currentUser != null) {
            return currentUser.id();
        }
        return music == null ? LOCAL_USER : login().id();
    }

    private void requireSpotify() {
        if (facade == null) {
            throw new IllegalStateException(
                    "Spotify is not configured. Copy .env.example to .env and set SPOTIFY_CLIENT_ID.");
        }
    }
}
