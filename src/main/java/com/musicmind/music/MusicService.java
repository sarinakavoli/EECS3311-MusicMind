package com.musicmind.music;

import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;

import java.util.List;

/**
 * Adapter-pattern target: the stable internal interface the whole application
 * depends on for music data and playlist actions. {@code SpotifyAdapter}
 * implements it over the Spotify Web API; other implementations can decorate or
 * mock it without touching any client code.
 */
public interface MusicService {

    // ---- read: listening data ----
    List<Track> getTopItems(String type, String timeRange);

    /**
     * The user's top artists for a time range ({@code short_term},
     * {@code medium_term}, {@code long_term}). Artists carry their genres, which
     * {@code ProfileBuilder} aggregates into the taste profile (F1). This sits
     * alongside {@link #getTopItems} because the Track-only return type there
     * cannot express an artist's genres.
     */
    List<Artist> getTopArtists(String timeRange);

    List<Track> getSavedTracks();
    List<Track> getRecentlyPlayed();

    // ---- read: catalogue ----
    List<Track> search(String query, int limit);
    List<Album> getArtistAlbums(String artistId);
    List<Track> getAlbumTracks(String albumId);

    // ---- write: playlists ----
    Playlist createPlaylist(String userId, String name);
    void addItems(String playlistId, List<String> uris);
    void removeItems(String playlistId, List<String> uris);
    void reorderItems(String playlistId, int from, int to);
    List<Track> getPlaylistItems(String playlistId);

    /** The current user's Spotify user id (needed to create playlists). */
    String currentUserId();
}
