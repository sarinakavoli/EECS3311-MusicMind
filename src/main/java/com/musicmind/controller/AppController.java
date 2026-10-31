package com.musicmind.controller;

import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.TrendComparator;
import com.musicmind.config.Config;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.OpenAiProvider;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.MusicService;
import com.musicmind.music.RetryingMusicService;
import com.musicmind.music.SpotifyAdapter;
import com.musicmind.tool.MemoryTool;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Application controller (MVC): the single wiring point shared by the GUI and
 * the CLI. It assembles the dependency graph (real Spotify and OpenAI when
 * configured, otherwise graceful fallbacks) and exposes the features as plain
 * method calls that return domain objects for a view to render. It holds no
 * business logic of its own.
 */
public class AppController {

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
            this.facade = new MusicAgentFacade(tools, memory, profileBuilder,
                    new TrendComparator(profileBuilder, config), new PromptBuilder(config), llm, config);
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
        Supplier<String> userId = () -> "local";
        if (config.spotifyConfigured()) {
            music = new RetryingMusicService(
                    new SpotifyAdapter(config.spotifyClientId(), config.spotifyRedirectUri()));
            userId = music::currentUserId;
        }
        MemoryManager memory = new MemoryManager(db,
                new PreferenceRepository(db, userId),
                new FeedbackRepository(db, userId),
                new SessionRepository(db, userId),
                new PlaylistHistoryRepository(db, userId));
        return new AppController(music, memory, llm, config);
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

    private void requireSpotify() {
        if (facade == null) {
            throw new IllegalStateException(
                    "Spotify is not configured. Copy .env.example to .env and set SPOTIFY_CLIENT_ID.");
        }
    }
}
