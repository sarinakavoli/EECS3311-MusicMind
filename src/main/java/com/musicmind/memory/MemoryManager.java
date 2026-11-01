package com.musicmind.memory;

import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.UserPreference;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;

import java.util.List;
import java.util.function.Supplier;

/**
 * Facade over the persistence layer: the agent remembers preferences, feedback,
 * sessions and playlist snapshots through these calls and never touches a
 * repository or SQL. If the database is unavailable the app keeps running
 * (F8 alternative case): reads fall back to safe defaults or empty results,
 * writes of preferences, feedback and sessions are skipped with a warning, and
 * {@link #warning()} says what happened. Snapshots are not skipped silently,
 * because undo and restore depend on them.
 */
public class MemoryManager {

    /** Safe defaults used while the database is down: no explicit content, balanced familiarity. */
    private static final List<UserPreference> DEFAULT_PREFERENCES =
            List.of(new UserPreference(UserPreference.DEFAULTS_KEY, "", false, 0.5));

    private final Database db;
    private final PreferenceRepository preferences;
    private final FeedbackRepository feedback;
    private final SessionRepository sessions;
    private final PlaylistHistoryRepository history;
    private volatile String warning;

    public MemoryManager(Database db, PreferenceRepository preferences, FeedbackRepository feedback,
                         SessionRepository sessions, PlaylistHistoryRepository history) {
        this.db = db;
        this.preferences = preferences;
        this.feedback = feedback;
        this.sessions = sessions;
        this.history = history;
    }

    // ---- preferences (F8) ----

    public List<UserPreference> loadPreferences(String userId) {
        return guarded("load preferences", () -> preferences.findByUser(userId), DEFAULT_PREFERENCES);
    }

    /** @throws IllegalArgumentException for a blank key or a {@code familiarBias} outside 0..1 */
    public void savePreference(UserPreference p) {
        validate(p);
        guarded("save the preference", () -> {
            preferences.save(p);
            return null;
        }, null);
    }

    // ---- feedback (F9) ----

    public List<Feedback> loadFeedback(String userId) {
        return guarded("load feedback", () -> feedback.findByUser(userId), List.of());
    }

    public void saveFeedback(Feedback f) {
        guarded("save the feedback", () -> {
            feedback.save(f);
            return null;
        }, null);
    }

    // ---- sessions and history (F1, F10, F12) ----

    public void saveSession(AgentSession s) {
        guarded("save the session", () -> {
            sessions.save(s);
            return null;
        }, null);
    }

    public List<AgentSession> loadHistory(String userId) {
        return guarded("load history", () -> sessions.findByUser(userId), List.of());
    }

    /** The current user's latest session that produced this playlist, or {@code null}. */
    public AgentSession findSessionByPlaylist(String playlistId) {
        return guarded("look up the session", () -> sessions.findByPlaylist(playlistId), null);
    }

    /** The current user's session with this id, or {@code null} (also while the database is down). */
    public AgentSession findSession(String sessionId) {
        return guarded("look up the session", () -> sessions.findById(sessionId), null);
    }

    // ---- snapshots (F12) ----

    /** @return the new snapshot's id */
    public int saveSnapshot(Playlist playlist) {
        return history.saveSnapshot(playlist);
    }

    /** The snapshot as a {@link Playlist} of id-only tracks, or {@code null} if there is no such snapshot. */
    public Playlist loadSnapshot(int id) {
        return history.findById(String.valueOf(id));
    }

    /**
     * The newest snapshot of a playlist, or {@code null} if it was never snapshotted. Like the
     * other snapshot calls it throws {@link PersistenceException} when the database is down,
     * because undo depends on it.
     */
    public Playlist loadLatestSnapshot(String playlistId) {
        return history.findLatestByPlaylist(playlistId);
    }

    /** The user's saved playlist versions for the history list, newest first; empty (with a warning) while the database is down. */
    public List<SnapshotInfo> loadSnapshotSummaries(String userId) {
        return guarded("load saved playlist versions", () -> history.summariesByUser(userId), List.of());
    }

    // ---- availability ----

    public boolean isAvailable() {
        return db.isAvailable();
    }

    /** The most recent database warning, or {@code null} once an operation has succeeded since. */
    public String warning() {
        return warning;
    }

    private <R> R guarded(String action, Supplier<R> op, R fallback) {
        try {
            R result = op.get();
            warning = null;
            return result;
        } catch (PersistenceException e) {
            warning = "Database unavailable: could not " + action + ". Continuing with defaults.";
            return fallback;
        }
    }

    private static void validate(UserPreference p) {
        if (p == null) {
            throw new IllegalArgumentException("Preference must not be null.");
        }
        if (p.key() == null || p.key().isBlank()) {
            throw new IllegalArgumentException("Preference key must not be blank.");
        }
        if (Double.isNaN(p.familiarBias()) || p.familiarBias() < 0 || p.familiarBias() > 1) {
            throw new IllegalArgumentException("familiarBias must be between 0 and 1.");
        }
    }
}
