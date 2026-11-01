package com.musicmind.memory.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.RequestKind;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * PostgreSQL repository for {@link AgentSession}: id, start time, status, request
 * (text and kind), plan steps, playlist id, the derived-data JSON and the decision
 * trace. The request text is the user's own words, not Spotify content.
 */
public class SessionRepository implements Repository<AgentSession> {

    private static final Type STRING_LIST = new TypeToken<List<String>>() { }.getType();
    private static final String COLUMNS = "id, started_at, status, request_text, request_kind, "
            + "plan_steps, playlist_id, derived_json, trace_json";

    private final Database db;
    private final Supplier<String> currentUser;
    private final Gson gson = new Gson();

    public SessionRepository(Database db, Supplier<String> currentUser) {
        this.db = db;
        this.currentUser = currentUser;
    }

    @Override
    public void save(AgentSession s) {
        String sql = "INSERT INTO agent_session(id, user_id, started_at, status, request_text, request_kind, "
                + "plan_steps, playlist_id, derived_json, trace_json) VALUES(?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, "
                + "request_text = EXCLUDED.request_text, request_kind = EXCLUDED.request_kind, "
                + "plan_steps = EXCLUDED.plan_steps, playlist_id = EXCLUDED.playlist_id, "
                + "derived_json = EXCLUDED.derived_json, trace_json = EXCLUDED.trace_json "
                + "WHERE agent_session.user_id = EXCLUDED.user_id";
        AgentRequest r = s.request();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, s.id());
            ps.setString(2, currentUser.get());
            ps.setTimestamp(3, Timestamp.from(s.startedAt()));
            ps.setString(4, s.status());
            ps.setString(5, r == null ? null : r.rawText());
            ps.setString(6, r == null || r.kind() == null ? null : r.kind().name());
            ps.setString(7, gson.toJson(s.planSteps()));
            ps.setString(8, s.playlistId());
            ps.setString(9, s.derivedData());
            ps.setString(10, s.trace() == null ? null : gson.toJson(s.trace()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save session " + s.id(), e);
        }
    }

    @Override
    public AgentSession findById(String id) {
        return queryOne("WHERE id = ? AND user_id = ?", id, currentUser.get());
    }

    /**
     * The user's most recent session that <em>created</em> this playlist, or {@code null}. Later edits of
     * the playlist are sessions too but do not replace it: the creating session holds the spec and the
     * decision trace that validation and explanation read.
     */
    public AgentSession findByPlaylist(String playlistId) {
        return queryOne("WHERE playlist_id = ? AND user_id = ? AND request_kind = ? ORDER BY started_at DESC LIMIT 1",
                playlistId, currentUser.get(), RequestKind.CREATE_PLAYLIST.name());
    }

    @Override
    public List<AgentSession> findByUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT " + COLUMNS + " FROM agent_session WHERE user_id = ? ORDER BY started_at DESC, id")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AgentSession> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load sessions for " + userId, e);
        }
    }

    @Override
    public void delete(String id) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM agent_session WHERE id = ? AND user_id = ?")) {
            ps.setString(1, id);
            ps.setString(2, currentUser.get());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete session " + id, e);
        }
    }

    private AgentSession queryOne(String where, String... args) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT " + COLUMNS + " FROM agent_session " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load session", e);
        }
    }

    private AgentSession read(ResultSet rs) throws SQLException {
        AgentSession s = new AgentSession(rs.getString("id"), rs.getTimestamp("started_at").toInstant(), null);
        s.setStatus(rs.getString("status"));
        String text = rs.getString("request_text");
        String kind = rs.getString("request_kind");
        if (text != null || kind != null) {
            s.setRequest(new AgentRequest(text, kind == null ? null : RequestKind.valueOf(kind)));
        }
        List<String> steps = gson.fromJson(rs.getString("plan_steps"), STRING_LIST);
        s.setPlanSteps(steps);
        s.setPlaylistId(rs.getString("playlist_id"));
        s.setDerivedData(rs.getString("derived_json"));
        String trace = rs.getString("trace_json");
        if (trace != null) {
            s.setTrace(gson.fromJson(trace, DecisionTrace.class));
        }
        return s;
    }
}
