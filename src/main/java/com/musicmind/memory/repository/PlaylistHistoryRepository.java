package com.musicmind.memory.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.memory.Database;
import com.musicmind.memory.PersistenceException;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * PostgreSQL repository for playlist snapshots (F12). A snapshot stores only the
 * playlist id, its name and the ordered track URIs, never titles or artists
 * (Spotify terms), so a loaded {@link Playlist} has id-only tracks with blank titles.
 * Snapshot ids are the numeric database ids, exposed as strings.
 */
public class PlaylistHistoryRepository implements Repository<Playlist> {

    private static final Type STRING_LIST = new TypeToken<List<String>>() { }.getType();

    private final Database db;
    private final Supplier<String> currentUser;
    private final Gson gson = new Gson();

    public PlaylistHistoryRepository(Database db, Supplier<String> currentUser) {
        this.db = db;
        this.currentUser = currentUser;
    }

    @Override
    public void save(Playlist playlist) {
        saveSnapshot(playlist);
    }

    /** Store a snapshot of the playlist and return its snapshot id. */
    public int saveSnapshot(Playlist playlist) {
        if (playlist.spotifyId() == null || playlist.spotifyId().isBlank()) {
            throw new IllegalArgumentException("Cannot snapshot a playlist that has no id yet.");
        }
        List<String> uris = playlist.tracks().stream().map(PlaylistHistoryRepository::uriOf).toList();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO playlist_snapshot(user_id, playlist_id, name, track_uris) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, currentUser.get());
            ps.setString(2, playlist.spotifyId());
            ps.setString(3, playlist.name());
            ps.setString(4, gson.toJson(uris));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save snapshot of playlist " + playlist.spotifyId(), e);
        }
    }

    @Override
    public Playlist findById(String snapshotId) {
        Integer id = parse(snapshotId);
        if (id == null) {
            return null;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT playlist_id, name, track_uris FROM playlist_snapshot WHERE id = ? AND user_id = ?")) {
            ps.setInt(1, id);
            ps.setString(2, currentUser.get());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load snapshot " + snapshotId, e);
        }
    }

    /** The most recent snapshot of this playlist, or {@code null} if none was saved. */
    public Playlist findLatestByPlaylist(String playlistId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT playlist_id, name, track_uris FROM playlist_snapshot "
                        + "WHERE playlist_id = ? AND user_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, playlistId);
            ps.setString(2, currentUser.get());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load the latest snapshot of playlist " + playlistId, e);
        }
    }

    @Override
    public List<Playlist> findByUser(String userId) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT playlist_id, name, track_uris FROM playlist_snapshot WHERE user_id = ? ORDER BY id DESC")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Playlist> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load snapshots for " + userId, e);
        }
    }

    @Override
    public void delete(String snapshotId) {
        Integer id = parse(snapshotId);
        if (id == null) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM playlist_snapshot WHERE id = ? AND user_id = ?")) {
            ps.setInt(1, id);
            ps.setString(2, currentUser.get());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete snapshot " + snapshotId, e);
        }
    }

    private Playlist read(ResultSet rs) throws SQLException {
        List<Track> tracks = new ArrayList<>();
        List<String> uris = gson.fromJson(rs.getString("track_uris"), STRING_LIST);
        for (String uri : uris) {
            tracks.add(new Track(uri.substring(uri.lastIndexOf(':') + 1), uri, "", 0, false, List.of()));
        }
        return new Playlist(rs.getString("playlist_id"), rs.getString("name"), tracks);
    }

    private static String uriOf(Track t) {
        return t.uri() != null && !t.uri().isBlank() ? t.uri() : "spotify:track:" + t.id();
    }

    private static Integer parse(String id) {
        try {
            return Integer.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
