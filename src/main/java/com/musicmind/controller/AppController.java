package com.musicmind.controller;

import com.musicmind.agent.ArtistGenreCache;
import com.musicmind.agent.ClarificationNeededException;
import com.musicmind.agent.ExplanationService;
import com.musicmind.agent.InfeasibleRequestException;
import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.agent.NegotiationHandler;
import com.musicmind.agent.Planner;
import com.musicmind.agent.PreferenceSettings;
import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.RecommendationEngine;
import com.musicmind.agent.ResponseParser;
import com.musicmind.agent.SearchRanker;
import com.musicmind.agent.TrendComparator;
import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.agent.command.PlaylistManager;
import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.SnapshotPreview;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.ValidationResult;
import com.musicmind.domain.Verdict;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.OpenAiProvider;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.memory.repository.CandidateRepository;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.GenreRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.GenreEnrichingMusicService;
import com.musicmind.music.LastFmAdapter;
import com.musicmind.music.MusicService;
import com.musicmind.music.OAuthTokenStore;
import com.musicmind.music.RetryingMusicService;
import com.musicmind.music.SpotifyAdapter;
import com.musicmind.music.TagService;
import com.musicmind.tool.MemoryTool;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    private static final String ARTIST_URI_PREFIX = "spotify:artist:";

    private final MusicService music; // null until Spotify is configured
    private final MemoryManager memory;
    private final MusicAgentFacade facade; // null when music is null
    private final ExplanationService explainer;
    private final boolean llmConfigured;
    private final Config config;
    private final BooleanSupplier browserLoginNeeded;
    private volatile User currentUser;
    private volatile List<String> lastPreferenceNotices = List.of();

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
        this(music, memory, llm, config, reauthenticator, () -> false);
    }

    /**
     * @param browserLoginNeeded says whether signing in will open the browser (no saved Spotify login),
     *                           so a view can warn the user only when that really happens
     */
    public AppController(MusicService music, MemoryManager memory, LLMProvider llm, Config config,
                         Runnable reauthenticator, BooleanSupplier browserLoginNeeded) {
        this(music, memory, llm, config, reauthenticator, browserLoginNeeded, ArtistGenreCache.NONE);
    }

    /**
     * @param genreCache read-only view of the cached Last.fm artist genres, which the goal review sends in place
     *                   of any Spotify title or name
     */
    public AppController(MusicService music, MemoryManager memory, LLMProvider llm, Config config,
                         Runnable reauthenticator, BooleanSupplier browserLoginNeeded, ArtistGenreCache genreCache) {
        this(music, memory, llm, config, reauthenticator, browserLoginNeeded, genreCache, TagService.NONE);
    }

    /**
     * @param tagService Last.fm's top songs for a tag, which Create matches to Spotify tracks; it returns nothing
     *                   (and makes no request) when no Last.fm key is configured
     */
    public AppController(MusicService music, MemoryManager memory, LLMProvider llm, Config config,
                         Runnable reauthenticator, BooleanSupplier browserLoginNeeded, ArtistGenreCache genreCache,
                         TagService tagService) {
        this.browserLoginNeeded = browserLoginNeeded;
        this.music = music;
        this.memory = memory;
        this.llmConfigured = llm != null;
        this.config = config;
        PromptBuilder explainPrompts = new PromptBuilder(config);
        ResponseParser explainParser = new ResponseParser(config);
        this.explainer = new ExplanationService(llm, explainPrompts, explainParser, config);
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
                    new RecommendationEngine(tools, config, genreCache, tagService),
                    () -> currentUser == null ? null : currentUser.id(),
                    new MusicAgentFacade.CreateSupport(music, new PlaylistManager(config, music, memory),
                            explainer, reauthenticator, genreCache, tagService));
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
        GenreRepository genreRepository = new GenreRepository(db);
        LastFmAdapter lastfm = new LastFmAdapter(config); // one Adapter: artist genres and song tags
        ArtistGenreCache genreCache = artistId -> {
            try {
                return genreRepository.find(artistId, Instant.now().minus(Duration.ofDays(config.lastfmCacheDays())));
            } catch (PersistenceException e) {
                return Optional.empty(); // no database, no cached genres
            }
        };
        BooleanSupplier browserLoginNeeded = () -> false;
        Supplier<String> userId = () -> LOCAL_USER;
        if (config.spotifyConfigured()) {
            // Spotify returns no artist genres to this app, so Last.fm artist tags supply them (R18).
            OAuthTokenStore auth = new OAuthTokenStore(config.spotifyClientId(), config.spotifyRedirectUri());
            music = new GenreEnrichingMusicService(new RetryingMusicService(new SpotifyAdapter(auth, config), config),
                    lastfm, genreRepository, config);
            userId = music::currentUserId;
            reauthenticator = auth::login;
            browserLoginNeeded = auth::needsBrowserLogin;
        }
        MemoryManager memory = new MemoryManager(db,
                new PreferenceRepository(db, userId),
                new FeedbackRepository(db, userId),
                new SessionRepository(db, userId),
                new PlaylistHistoryRepository(db, userId),
                new CandidateRepository(db, userId));
        return new AppController(music, memory, llm, config, reauthenticator, browserLoginNeeded, genreCache,
                lastfm);
    }

    public boolean isSpotifyConfigured() {
        return music != null;
    }

    /** True when {@link #login()} will open the browser, because there is no saved Spotify login. */
    public boolean willOpenBrowser() {
        return music != null && currentUser == null && browserLoginNeeded.getAsBoolean();
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

    /** The line both interfaces show to credit Last.fm for the song tags. */
    public String lastfmAttribution() {
        return config.lastfmAttribution();
    }

    /** The time ranges Your Taste offers, most recent first (the past week, then Spotify's three). */
    public List<String> timeRanges() {
        return config.profileTasteRanges();
    }

    /** The ranges Trends compares: Spotify's three only, since a week of plays is too thin to compare. */
    public List<String> trendRanges() {
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

    /** The signed-in user's Spotify display name (falls back to the id). */
    public String currentUserName() {
        requireSpotify();
        return music.currentUserDisplayName();
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

    /** The artists the last search named. */
    public List<Artist> lastSearchArtists() {
        return facade == null ? List.of() : facade.lastSearchArtists();
    }

    /** The "plain keyword search" label if the last search was not interpreted; otherwise null. */
    public String lastSearchLabel() {
        return facade == null ? null : facade.lastSearchLabel();
    }

    /**
     * F3 — sets a track aside for the next playlist (a Spotify track URI is accepted too).
     *
     * @throws IllegalArgumentException with a message fit to show if the id is not a track id
     */
    public void addCandidate(String trackId) {
        memory.addCandidate(requireTrackId(trackId));
    }

    public void removeCandidate(String trackId) {
        memory.removeCandidate(requireTrackId(trackId));
    }

    public void clearCandidates() {
        memory.clearCandidates();
    }

    /** F3 — the tracks set aside, oldest first, with their details from Spotify. */
    public List<Track> getCandidates() {
        requireSpotify();
        signInIfNeeded();
        return facade.candidates(currentUser.id());
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
     * @throws ClarificationNeededException if the goal is unclear
     * @throws InfeasibleRequestException if the request cannot be met
     * @throws NegotiationCancelledException if the user declined every option
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

    // ---- F6 and F7 ----

    /**
     * F6 — changes a playlist from a sentence (signing in if needed). A snapshot is saved first, so the change
     * can be undone with {@link #undo}. What changed is {@link #lastChangeSummary()}; labels and warnings are
     * {@link #lastModifyNotices()}.
     *
     * @throws IllegalStateException if there is no LLM key
     * @throws ClarificationNeededException if the instruction is ambiguous or unclear
     * @throws PlaylistChangeException if the playlist cannot be read or changed
     */
    public Playlist modifyPlaylist(String id, String instruction) {
        requireSpotify();
        signInIfNeeded();
        return facade.modifyPlaylist(id, instruction);
    }

    /** The playlists the user owns or collaborates on, for choosing one to open. */
    public List<PlaylistSummary> getPlaylists() {
        requireSpotify();
        signInIfNeeded();
        return facade.userPlaylists();
    }

    /** A playlist and its tracks as Spotify holds them now. */
    public Playlist getPlaylist(String id) {
        requireSpotify();
        signInIfNeeded();
        return facade.playlist(id);
    }

    /** What the last {@link #getPlaylist} could not include. */
    public List<String> lastPlaylistNotices() {
        return facade == null ? List.of() : facade.lastPlaylistNotices();
    }

    /** What the last modify changed, in words; empty when nothing was changed. */
    public String lastChangeSummary() {
        return facade == null ? "" : facade.lastChangeSummary();
    }

    /** The approximate-edit label, the constraints the last modify now breaks, and any database warning. */
    public List<String> lastModifyNotices() {
        return facade == null ? List.of() : facade.lastModifyNotices();
    }

    /**
     * F6 — reverts the latest change to a playlist: the last one made in this run, else the playlist's
     * latest saved version.
     *
     * @throws PlaylistChangeException with a short message if there is nothing
     *         to undo or the saved history cannot be reached
     */
    public void undo(String playlistId) {
        requireSpotify();
        signInIfNeeded();
        facade.undo(playlistId);
    }

    /**
     * Deletes one of the user's own playlists from their Spotify library. The playlist is saved first, so
     * {@link #restore} (and {@link #recreateFromSnapshot}) can bring it back.
     *
     * @return the id of the saved version
     * @throws PlaylistChangeException if the playlist is not the user's own, is too
     *         long to save whole, or cannot be saved first (nothing is deleted then)
     */
    public int deletePlaylist(String playlistId) {
        requireSpotify();
        signInIfNeeded();
        return facade.deletePlaylist(playlistId);
    }

    /**
     * F7 — checks a playlist on Spotify against the constraints saved when it was created, else against the
     * stored preferences ({@link #lastValidationNote()} says which).
     */
    public ValidationResult validatePlaylist(String id) {
        requireSpotify();
        signInIfNeeded();
        return facade.validatePlaylist(id);
    }

    /** Which constraints the last validation used; null before one. */
    public String lastValidationNote() {
        return facade == null ? null : facade.lastValidationNote();
    }

    /** The target length, in minutes, the last validation judged by (0: none), so a view can say how far off it is. */
    public int lastValidationTargetMinutes() {
        return facade == null ? 0 : facade.lastValidationTargetMinutes();
    }

    // ---- F10 ----

    /**
     * F10 — why a playlist's tracks were chosen, from the decision trace stored with the run that created it.
     *
     * @param id a session id or a playlist id
     * @throws IllegalArgumentException if nothing stored matches the id
     * @throws IllegalStateException if the stored record cannot be reached
     * (reads stored data only: no Spotify sign-in is needed beyond what the data store itself uses)
     */
    public String explain(String id) {
        return explainer.explain(getSession(id));
    }

    /**
     * F12 — one stored session with its plan and result.
     *
     * @param id a session id or a playlist id (the session that created the playlist)
     * @throws IllegalArgumentException if nothing stored matches the id
     * @throws IllegalStateException if the stored record cannot be reached
     */
    public AgentSession getSession(String id) {
        AgentSession session = memory.findSession(id);
        if (session == null) {
            session = memory.findSessionByPlaylist(id);
        }
        if (session == null) {
            if (memory.warning() != null) {
                throw new IllegalStateException(config.wording("explainDbDown"));
            }
            throw new IllegalArgumentException(String.format(config.wording("explainUnknown"), id));
        }
        return session;
    }

    // ---- F12 ----

    /** F12 — the user's past agent sessions, newest first; empty (with {@code memory().warning()} set) while the database is down. */
    public List<AgentSession> getHistory(User user) {
        return memory.loadHistory(user.id());
    }

    /** The user's saved playlist versions (id, playlist, name, size, time), newest first, for choosing one to restore. */
    public List<SnapshotInfo> getSnapshots(User user) {
        return memory.loadSnapshotSummaries(user.id());
    }

    /**
     * F12 — puts a playlist back to a saved version; the current content is saved first so this can be undone.
     *
     * @throws PlaylistMissingException if the Spotify playlist was deleted
     *         (offer {@link #recreateFromSnapshot})
     * @throws PlaylistChangeException if the snapshot is unknown or unavailable
     */
    public Playlist restore(int snapshotId) {
        requireSpotify();
        signInIfNeeded();
        return facade.restore(snapshotId);
    }

    /** The songs of a saved version, titled from Spotify where it can be read, for a preview; changes nothing. */
    public SnapshotPreview previewSnapshot(int snapshotId) {
        requireSpotify();
        signInIfNeeded();
        return facade.previewSnapshot(snapshotId);
    }

    /** Whether the last {@link #restore} changed the playlist; false when it already matched the saved version. */
    public boolean lastRestoreChanged() {
        return facade.lastRestoreChanged();
    }

    /** F12 — creates a new playlist from a snapshot whose playlist was deleted on Spotify. */
    public Playlist recreateFromSnapshot(int snapshotId) {
        requireSpotify();
        signInIfNeeded();
        return facade.recreateFromSnapshot(snapshotId);
    }

    // ---- F8 ----

    /**
     * Turns what a user typed into a preference.
     *
     * @throws IllegalArgumentException with a message fit to show, for an unknown key or unusable value
     */
    public UserPreference parsePreference(String key, String value) {
        lastPreferenceNotices = List.of();
        if (facade == null || key == null || !key.strip().equals(PreferenceSettings.BLOCKED_ARTISTS)) {
            return PreferenceSettings.parse(key, value, config);
        }
        UserPreference parsed = PreferenceSettings.parse(key, facade.artistIds(value), config);
        lastPreferenceNotices = facade.lastArtistNotices();
        return parsed;
    }

    /**
     * Blocked songs as a view lists them, id to "Title \u2013 Artist, Artist", read from Spotify now and never stored.
     * Empty when Spotify is not set up; a song that cannot be read is absent, and the reading stops early when
     * Spotify refuses (login expired, rate limit, server error), so the view shows its placeholder for the rest.
     */
    public Map<String, String> trackLines(List<String> trackIds) {
        if (facade == null || trackIds == null) {
            return new LinkedHashMap<>();
        }
        List<String> ids = trackIds.stream().filter(id -> id != null && !id.isBlank()).toList();
        return ids.isEmpty() ? new LinkedHashMap<>() : facade.trackLines(ids);
    }

    /** The value a view shows for a preference: blocked artists by name, which are never stored. */
    public String displayPreference(UserPreference pref) {
        if (facade == null || !pref.key().equals(PreferenceSettings.BLOCKED_ARTISTS) || pref.value().isBlank()) {
            return PreferenceSettings.display(pref);
        }
        return Arrays.stream(pref.value().split(",")).map(facade::artistName)
                .collect(Collectors.joining(", "));
    }

    /**
     * Stores a preference (an existing key is updated). With the database down the app keeps going and
     * {@code memory().warning()} says it was not saved.
     *
     * @throws IllegalArgumentException for a blank key or a familiar bias outside 0..1
     */
    public void setPreference(UserPreference pref) {
        if (PreferenceSettings.isEmptyList(pref)) {
            memory.deletePreference(pref.key());
        } else {
            memory.savePreference(pref);
        }
    }

    /** The familiar share the user saved, or empty when none is saved (the safe-defaults record does not count). */
    public OptionalDouble savedFamiliarBias() {
        return getPreferences().stream().filter(p -> p.key().equals(PreferenceSettings.FAMILIAR_BIAS)).findFirst()
                .map(p -> OptionalDouble.of(p.familiarBias())).orElse(OptionalDouble.empty());
    }

    /** Every artist Spotify finds for a name, those named exactly that first, so the user can choose one. */
    public List<Artist> findArtists(String name) {
        requireSpotify();
        return facade.findArtists(name);
    }

    /** A short display-only line (an album title, and whether the artist is in the user's listening) to tell same-named artists apart; blank if none can be read. */
    public String artistHint(String artistId) {
        return artistHints(List.of(artistId)).get(artistId);
    }

    /** {@link #artistHint} for several artists at once: the saved profile is read once, not once per artist. */
    public Map<String, String> artistHints(List<String> artistIds) {
        requireSpotify();
        Set<String> listening = facade.listeningArtistIds(currentUser());
        Map<String, String> hints = new LinkedHashMap<>();
        for (String id : artistIds) {
            hints.put(id, facade.artistHint(id, listening));
        }
        return hints;
    }

    /** Tracks for what the user typed, the same ranked list as {@link #searchMusic}. */
    public List<Track> findTracks(String text) {
        return searchMusic(text);
    }

    /**
     * Adds the chosen artist to the blocked artists, keeping the ones already blocked.
     *
     * @throws IllegalArgumentException if the id is not shaped like a Spotify artist id (or {@code spotify:artist:} URI)
     * @throws IllegalStateException    if the stored blocks cannot be read, so nothing is saved
     */
    public void blockArtist(String artistId) {
        String id = artistId == null ? "" : artistId.strip();
        if (!config.prefsArtistIdPattern().matcher(id).matches()) {
            throw new IllegalArgumentException(config.wording("prefsBadArtistId"));
        }
        addBlock(PreferenceSettings.BLOCKED_ARTISTS, ARTIST_URI_PREFIX, id);
    }

    /**
     * Adds the chosen track to the blocked tracks, keeping the ones already blocked.
     *
     * @throws IllegalArgumentException if the id is not shaped like a Spotify track id
     * @throws IllegalStateException    if the stored blocks cannot be read, so nothing is saved
     */
    public void blockTrack(String trackId) {
        addBlock(PreferenceSettings.BLOCKED_TRACKS, TRACK_URI_PREFIX, requireTrackId(trackId));
    }

    /**
     * Removes the artist from the blocked artists, keeping the others; removing the last one clears the rule.
     *
     * @throws IllegalArgumentException if the id is not shaped like a Spotify artist id (or {@code spotify:artist:} URI)
     * @throws IllegalStateException    if the stored blocks cannot be read, so nothing is saved
     */
    public void unblockArtist(String artistId) {
        String id = artistId == null ? "" : artistId.strip();
        if (!config.prefsArtistIdPattern().matcher(id).matches()) {
            throw new IllegalArgumentException(config.wording("prefsBadArtistId"));
        }
        changeBlocks(PreferenceSettings.BLOCKED_ARTISTS, ARTIST_URI_PREFIX, id, false);
    }

    /**
     * Removes the track from the blocked tracks, keeping the others; removing the last one clears the rule.
     *
     * @throws IllegalArgumentException if the id is not shaped like a Spotify track id
     * @throws IllegalStateException    if the stored blocks cannot be read, so nothing is saved
     */
    public void unblockTrack(String trackId) {
        changeBlocks(PreferenceSettings.BLOCKED_TRACKS, TRACK_URI_PREFIX, requireTrackId(trackId), false);
    }

    private void addBlock(String key, String uriPrefix, String id) {
        changeBlocks(key, uriPrefix, id, true);
    }

    /**
     * Saves the blocked ids with {@code id} added or removed. If the stored preferences cannot be read the database is
     * down and {@link #getPreferences()} returns the safe defaults; saving on top of those would wipe the blocks that
     * are stored, so nothing is saved.
     */
    private void changeBlocks(String key, String uriPrefix, String id, boolean add) {
        List<UserPreference> stored = getPreferences();
        if (stored.stream().anyMatch(p -> UserPreference.DEFAULTS_KEY.equals(p.key()))) {
            throw new IllegalStateException(config.wording("prefsUnavailable"));
        }
        PreferenceSettings settings = PreferenceSettings.from(stored);
        Set<String> all = new TreeSet<>(PreferenceSettings.BLOCKED_ARTISTS.equals(key) ? settings.blockedArtists() : settings.blockedTracks());
        String bare = id.startsWith(uriPrefix) ? id.substring(uriPrefix.length()) : id;
        if (add) {
            all.add(bare);
        } else {
            all.remove(bare);
        }
        setPreference(PreferenceSettings.parse(key, String.join(",", all), config));
    }

    /** What the last {@link #parsePreference} should tell the user, such as a name several artists share. */
    public List<String> lastPreferenceNotices() {
        return lastPreferenceNotices;
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
        String id = requireTrackId(trackId);
        if (verdict == null) {
            throw new IllegalArgumentException("Choose accept or reject.");
        }
        memory.saveFeedback(new Feedback(id, verdict, Instant.now()));
    }

    /**
     * Track id to the user's latest verdict, so screens can show what was chosen; empty with the database down. Reads
     * under the id {@link #recordFeedback} stores under (the local user without Spotify), so a stored verdict always
     * shows, and never signs in: while that would open the browser, nothing is read.
     */
    public Map<String, Verdict> feedbackFor() {
        Map<String, Verdict> latest = new LinkedHashMap<>();
        String id = feedbackUserId();
        if (id == null) {
            return latest;
        }
        for (Feedback f : memory.loadFeedback(id)) {   // oldest first, so the last one wins
            latest.put(f.trackId(), f.verdict());
        }
        return latest;
    }

    /** The id feedback is stored under, found without opening the browser; null when it cannot be known yet. */
    private String feedbackUserId() {
        if (currentUser != null) {
            return currentUser.id();
        }
        if (music == null) {
            return LOCAL_USER;
        }
        if (willOpenBrowser()) {
            return null;
        }
        try {
            return music.currentUserId();   // the id the repositories stamp on recordFeedback
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The bare track id for a typed id or {@code spotify:track:} URI, stripped of spaces. Views call this to
     * reject a bad id before doing anything else.
     *
     * @throws IllegalArgumentException with a message fit to show if it is blank or not shaped like a Spotify track id
     */
    public String requireTrackId(String trackId) {
        if (trackId == null || trackId.isBlank()) {
            throw new IllegalArgumentException("Give the id of the track.");
        }
        String id = trackId.strip();
        if (!config.feedbackTrackIdPattern().matcher(id).matches()) {
            throw new IllegalArgumentException(config.wording("feedbackBadTrackId"));
        }
        return id.startsWith(TRACK_URI_PREFIX) ? id.substring(TRACK_URI_PREFIX.length()) : id;
    }

    /** The id data is stored under: the logged-in user (signing in if needed), or the local user without Spotify. */
    private String userId() {
        if (currentUser != null) {
            return currentUser.id();
        }
        return music == null ? LOCAL_USER : login().id();
    }

    private void signInIfNeeded() {
        if (currentUser == null) {
            login();
        }
    }

    private void requireSpotify() {
        if (facade == null) {
            throw new IllegalStateException(
                    "Spotify is not configured. Copy .env.example to .env and set SPOTIFY_CLIENT_ID.");
        }
    }
}
