package com.musicmind.memory.repository;

import com.musicmind.domain.UserPreference;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** PostgreSQL repository for standing preferences: one row per (user, key); the id is the key. */
public class PreferenceRepository implements Repository<UserPreference> {

    private static final String COLUMNS = "key, value, explicit_allowed, familiar_bias";

    private final Database db;
    private final Supplier<String> currentUser;

    public PreferenceRepository(Database db, Supplier<String> currentUser) {
        this.db = db;
        this.currentUser = currentUser;
    }

    @Override
    public void save(UserPreference pref) {
        String sql = "INSERT INTO user_preference(user_id, key, value, explicit_allowed, familiar_bias) "
                + "VALUES(?,?,?,?,?) ON CONFLICT (user_id, key) DO UPDATE SET "
                + "value = EXCLUDED.value, explicit_allowed = EXCLUDED.explicit_allowed, "
                + "familiar_bias = EXCLUDED.familiar_bias";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, currentUser.get());
            ps.setString(2, pref.key());
            ps.setString(3, pref.value());
            ps.setBoolean(4, pref.explicitAllowed());
            ps.setDouble(5, pref.familiarBias());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save preference '" + pref.key() + "'", e);
        }
    }

    @Override
    public UserPreference findById(String key) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT " + COLUMNS + " FROM user_preference WHERE user_id = ? AND key = ?")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load preference '" + key + "'", e);
        }
    }

    @Override
    public List<UserPreference> findByUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT " + COLUMNS + " FROM user_preference WHERE user_id = ? ORDER BY key")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<UserPreference> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load preferences for " + userId, e);
        }
    }

    @Override
    public void delete(String key) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM user_preference WHERE user_id = ? AND key = ?")) {
            ps.setString(1, currentUser.get());
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete preference '" + key + "'", e);
        }
    }

    private static UserPreference read(ResultSet rs) throws SQLException {
        return new UserPreference(rs.getString("key"), rs.getString("value"),
                rs.getBoolean("explicit_allowed"), rs.getDouble("familiar_bias"));
    }
}
