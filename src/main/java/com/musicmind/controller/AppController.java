package com.musicmind.controller;

import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.ProfileSummarizer;
import com.musicmind.config.Config;
import com.musicmind.llm.GeminiLLMProvider;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.MockLLMProvider;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.repository.SqlitePreferenceRepository;
import com.musicmind.memory.repository.SqliteProfileRepository;
import com.musicmind.music.MusicService;
import com.musicmind.music.RetryingMusicService;
import com.musicmind.music.SpotifyAdapter;

/**
 * Application controller (MVC): the single wiring point shared by the GUI and
 * the CLI. It reads configuration, assembles the dependency graph (real Spotify
 * + Gemini when configured, otherwise graceful fallbacks), and exposes the
 * features as plain method calls that return result objects for a view to
 * render.
 */
public class AppController {

    private final MemoryManager memory;
    private final MusicAgentFacade facade; // null until Spotify is configured
    private final boolean spotifyConfigured;
    private final boolean llmConfigured;

    private AppController(MemoryManager memory, MusicAgentFacade facade,
                          boolean spotifyConfigured, boolean llmConfigured) {
        this.memory = memory;
        this.facade = facade;
        this.spotifyConfigured = spotifyConfigured;
        this.llmConfigured = llmConfigured;
    }

    /** Build a controller from the local {@code .env} configuration. */
    public static AppController create() {
        Config config = Config.load();

        LLMProvider llm = config.llmConfigured()
                ? new GeminiLLMProvider(config.llmApiKey(), config.llmModel())
                : new MockLLMProvider();

        Database db = new Database(config.databaseUrl());
        db.init();
        MemoryManager memory = new MemoryManager(
                new SqliteProfileRepository(db), new SqlitePreferenceRepository(db));

        MusicAgentFacade facade = null;
        if (config.spotifyConfigured()) {
            MusicService music = new RetryingMusicService(
                    new SpotifyAdapter(config.spotifyClientId(), config.spotifyRedirectUri()));
            facade = new MusicAgentFacade(music, memory, new ProfileBuilder(music), new ProfileSummarizer(llm));
        }
        return new AppController(memory, facade, config.spotifyConfigured(), config.llmConfigured());
    }

    public boolean isSpotifyConfigured() {
        return spotifyConfigured;
    }

    public boolean isLlmConfigured() {
        return llmConfigured;
    }

    /** F1 — View Taste Profile. {@code timeRange} is short/medium/long. */
    public TasteProfileResult viewTasteProfile(String timeRange) {
        requireSpotify();
        return facade.viewTasteProfile(timeRange);
    }

    public MemoryManager memory() {
        return memory;
    }

    private void requireSpotify() {
        if (facade == null) {
            throw new IllegalStateException(
                    "Spotify is not configured. Copy .env.example to .env and set SPOTIFY_CLIENT_ID.");
        }
    }
}
