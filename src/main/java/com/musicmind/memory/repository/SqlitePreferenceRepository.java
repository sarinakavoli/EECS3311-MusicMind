package com.musicmind.memory.repository;

import com.musicmind.domain.UserPreference;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed {@link Repository} for standing user preferences (one row per
 * {@code (user, key)}). Used by the preferences feature (F8) and read by the
 * recommendation strategies; included now so MemoryManager has real substance.
 */
public class SqlitePreferenceRepository implements Repository<UserPreference> {

    private final Database db;

    public SqlitePreferenceRepository(Database db) {
        this.db = db;
    }

    @Override
    public void save(String userId, UserPreference pref) {
        String sql = "INSERT OR REPLACE INTO user_preference"
                + "(user_id, key, value, explicit_allowed, familiar_bias) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, pref.key());
            ps.setString(3, pref.value());
            ps.setInt(4, pref.explicitAllowed() ? 1 : 0);
            ps.setDouble(5, pref.familiarBias());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save preference '" + pref.key() + "' for " + userId, e);
        }
    }

    @Override
    public List<UserPreference> findByUser(String userId) {
        String sql = "SELECT key, value, explicit_allowed, familiar_bias FROM user_preference WHERE user_id = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<UserPreference> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new UserPreference(
                            rs.getString("key"),
                            rs.getString("value"),
                            rs.getInt("explicit_allowed") == 1,
                            rs.getDouble("familiar_bias")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load preferences for " + userId, e);
        }
    }

    @Override
    public void deleteForUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM user_preference WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete preferences for " + userId, e);
        }
    }
}
