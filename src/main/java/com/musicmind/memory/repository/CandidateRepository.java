package com.musicmind.memory.repository;

import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * PostgreSQL repository for the user's candidate set (F3): track ids set aside from search for the next
 * playlist. Only ids are stored (Spotify terms); titles are read from Spotify when shown.
 */
public class CandidateRepository implements Repository<String> {

    private final Database db;
    private final Supplier<String> currentUser;

    public CandidateRepository(Database db, Supplier<String> currentUser) {
        this.db = db;
        this.currentUser = currentUser;
    }

    /** Adds the track; one already in the set keeps its place. */
    @Override
    public void save(String trackId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO candidate_track(user_id, track_id) VALUES(?,?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, trackId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save candidate " + trackId, e);
        }
    }

    @Override
    public String findById(String trackId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT track_id FROM candidate_track WHERE user_id = ? AND track_id = ?")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load candidate " + trackId, e);
        }
    }

    /** The user's candidate track ids, oldest first. */
    @Override
    public List<String> findByUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT track_id FROM candidate_track WHERE user_id = ? ORDER BY position")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load candidates for " + userId, e);
        }
    }

    @Override
    public void delete(String trackId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM candidate_track WHERE user_id = ? AND track_id = ?")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, trackId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to remove candidate " + trackId, e);
        }
    }

    /** Empties the current user's set. */
    public void clear() {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM candidate_track WHERE user_id = ?")) {
            ps.setString(1, currentUser.get());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to clear candidates", e);
        }
    }
}
