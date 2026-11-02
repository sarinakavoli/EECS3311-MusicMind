package com.musicmind.music;

import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.Track;

import java.util.List;

/**
 * Test-only no-op {@link MusicService}: every method throws until a test
 * overrides the one it exercises. Keeps individual tests short.
 */
public class StubMusicService implements MusicService {
    @Override public List<Track> getTopItems(String type, String timeRange) { throw new UnsupportedOperationException(); }
    @Override public List<Artist> getTopArtists(String timeRange) { throw new UnsupportedOperationException(); }
    @Override public List<Track> getSavedTracks() { throw new UnsupportedOperationException(); }
    @Override public List<Track> getRecentlyPlayed() { throw new UnsupportedOperationException(); }
    @Override public List<Track> search(String query, int limit) { throw new UnsupportedOperationException(); }
    @Override public List<Album> getArtistAlbums(String artistId) { throw new UnsupportedOperationException(); }
    @Override public List<Track> getAlbumTracks(String albumId) { throw new UnsupportedOperationException(); }
    @Override public Playlist createPlaylist(String userId, String name) { throw new UnsupportedOperationException(); }
    @Override public void addItems(String playlistId, List<String> uris) { throw new UnsupportedOperationException(); }
    @Override public void removeItems(String playlistId, List<String> uris) { throw new UnsupportedOperationException(); }
    @Override public void reorderItems(String playlistId, int from, int to) { throw new UnsupportedOperationException(); }
    @Override public List<Track> getPlaylistItems(String playlistId) { throw new UnsupportedOperationException(); }
    @Override public void unfollowPlaylist(String playlistId) { throw new UnsupportedOperationException(); }
    @Override public List<Artist> searchArtists(String query, int limit) { throw new UnsupportedOperationException(); }
    @Override public Artist getArtist(String artistId) { throw new UnsupportedOperationException(); }
    @Override public Track getTrack(String trackId) { throw new UnsupportedOperationException(); }
    @Override public List<PlaylistSummary> getUserPlaylists() { throw new UnsupportedOperationException(); }
    @Override public String currentUserId() { throw new UnsupportedOperationException(); }
}
