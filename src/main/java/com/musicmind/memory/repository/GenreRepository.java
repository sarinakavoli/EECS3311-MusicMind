package com.musicmind.memory.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL cache of artist genres from MusicBrainz, keyed by Spotify artist id.
 * Genres are MusicMind derived data, so they may be stored; artist names are not.
 * An empty list is a stored "no match". The cache is shared by all users, so this
 * is deliberately not a per-user {@link Repository}.
 */
public class GenreRepository {

    private static final java.lang.reflect.Type LIST = new TypeToken<List<String>>() { }.getType();

    private final Database db;
    private final Gson gson = new Gson();

    public GenreRepository(Database db) {
        this.db = db;
    }

    public void save(String artistId, List<String> genres, Instant fetchedAt) {
        String sql = "INSERT INTO artist_genre(artist_id, genres, fetched_at) VALUES(?,?,?) "
                + "ON CONFLICT (artist_id) DO UPDATE SET genres = EXCLUDED.genres, fetched_at = EXCLUDED.fetched_at";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, artistId);
            ps.setString(2, gson.toJson(genres));
            ps.setTimestamp(3, Timestamp.from(fetchedAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to cache genres for artist " + artistId, e);
        }
    }

    /**
     * The cached genres if there is an entry fetched at or after {@code freshAfter};
     * empty on a miss or an expired entry. A present empty list is a cached no-match.
     */
    public Optional<List<String>> find(String artistId, Instant freshAfter) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT genres FROM artist_genre WHERE artist_id = ? AND fetched_at >= ?")) {
            ps.setString(1, artistId);
            ps.setTimestamp(2, Timestamp.from(freshAfter));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(List.copyOf(gson.<List<String>>fromJson(rs.getString(1), LIST)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read cached genres for artist " + artistId, e);
        }
    }
}
