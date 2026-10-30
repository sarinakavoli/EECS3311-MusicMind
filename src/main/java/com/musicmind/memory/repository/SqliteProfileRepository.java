package com.musicmind.memory.repository;

import com.google.gson.Gson;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed {@link Repository} for taste profiles (one per user). The
 * profile's genre histogram, top artists and top tracks are stored as a single
 * JSON column so the schema stays simple; MemoryManager uses this to cache the
 * last profile built for a user.
 */
public class SqliteProfileRepository implements Repository<ListeningProfile> {

    /** Flat JSON shape stored in the {@code json} column (records serialise cleanly with Gson). */
    private record ProfileDto(Map<String, Double> genres, List<Artist> artists,
                              List<Track> tracks, long generatedAtMillis) {
    }

    private final Database db;
    private final Gson gson = new Gson();

    public SqliteProfileRepository(Database db) {
        this.db = db;
    }

    @Override
    public void save(String userId, ListeningProfile profile) {
        ProfileDto dto = new ProfileDto(
                profile.genreHistogram(), profile.topArtists(), profile.topTracks(),
                profile.generatedAt() == null ? Instant.now().toEpochMilli() : profile.generatedAt().toEpochMilli());
        String json = gson.toJson(dto);
        String sql = "INSERT OR REPLACE INTO taste_profile(user_id, generated_at, json) VALUES(?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, Instant.ofEpochMilli(dto.generatedAtMillis()).toString());
            ps.setString(3, json);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save taste profile for " + userId, e);
        }
    }

    @Override
    public List<ListeningProfile> findByUser(String userId) {
        String sql = "SELECT json FROM taste_profile WHERE user_id = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ListeningProfile> out = new ArrayList<>();
                if (rs.next()) {
                    ProfileDto dto = gson.fromJson(rs.getString("json"), ProfileDto.class);
                    out.add(new ListeningProfile(dto.genres(), dto.artists(), dto.tracks(),
                            Instant.ofEpochMilli(dto.generatedAtMillis())));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load taste profile for " + userId, e);
        }
    }

    @Override
    public void deleteForUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM taste_profile WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete taste profile for " + userId, e);
        }
    }
}
