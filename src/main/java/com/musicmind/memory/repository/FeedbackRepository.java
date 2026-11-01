package com.musicmind.memory.repository;

import com.musicmind.domain.Feedback;
import com.musicmind.domain.Verdict;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** PostgreSQL repository for accept/reject verdicts; upserts by (user, track) and the id is the track id. */
public class FeedbackRepository implements Repository<Feedback> {

    private final Database db;
    private final Supplier<String> currentUser;

    public FeedbackRepository(Database db, Supplier<String> currentUser) {
        this.db = db;
        this.currentUser = currentUser;
    }

    @Override
    public void save(Feedback f) {
        String sql = "INSERT INTO feedback(user_id, track_id, verdict, created_at) VALUES(?,?,?,?) "
                + "ON CONFLICT (user_id, track_id) DO UPDATE SET "
                + "verdict = EXCLUDED.verdict, created_at = EXCLUDED.created_at";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, currentUser.get());
            ps.setString(2, f.trackId());
            ps.setString(3, f.verdict().name());
            ps.setTimestamp(4, Timestamp.from(f.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save feedback for track " + f.trackId(), e);
        }
    }

    @Override
    public Feedback findById(String trackId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT track_id, verdict, created_at FROM feedback WHERE user_id = ? AND track_id = ?")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load feedback for track " + trackId, e);
        }
    }

    @Override
    public List<Feedback> findByUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT track_id, verdict, created_at FROM feedback WHERE user_id = ? ORDER BY created_at, track_id")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Feedback> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load feedback for " + userId, e);
        }
    }

    @Override
    public void delete(String trackId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM feedback WHERE user_id = ? AND track_id = ?")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, trackId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete feedback for track " + trackId, e);
        }
    }

    private static Feedback read(ResultSet rs) throws SQLException {
        return new Feedback(rs.getString("track_id"), Verdict.valueOf(rs.getString("verdict")),
                rs.getTimestamp("created_at").toInstant());
    }
}
