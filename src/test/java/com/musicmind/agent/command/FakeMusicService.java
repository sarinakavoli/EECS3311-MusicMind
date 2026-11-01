package com.musicmind.agent.command;

import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double that behaves like Spotify's playlist endpoints (appending adds, remove-by-URI,
 * range-move with insert_before semantics) and records every call, so command tests can check
 * both the calls made and the resulting order.
 */
class FakeMusicService extends StubMusicService {

    final Map<String, List<Track>> playlists = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    final Map<String, List<Track>> searchResults = new HashMap<>();
    /** When set, the call whose recorded text starts with this prefix throws. */
    String failOn;
    private int nextId = 1;

    static Track track(String id) {
        return new Track(id, "spotify:track:" + id, "Title " + id, 180000, false, List.of());
    }

    List<String> ids(String playlistId) {
        return playlists.get(playlistId).stream().map(Track::id).toList();
    }

    void seed(String playlistId, String... trackIds) {
        List<Track> list = new ArrayList<>();
        for (String id : trackIds) {
            list.add(track(id));
        }
        playlists.put(playlistId, list);
    }

    private void record(String call) {
        calls.add(call);
        if (failOn != null && call.startsWith(failOn)) {
            throw new MusicServiceException("boom on " + call);
        }
    }

    @Override public Playlist createPlaylist(String userId, String name) {
        record("create " + userId + " " + name);
        String id = "pl" + nextId++;
        playlists.put(id, new ArrayList<>());
        return new Playlist(id, name, List.of());
    }

    @Override public void addItems(String playlistId, List<String> uris) {
        record("add " + playlistId + " " + uris);
        for (String uri : uris) {
            playlists.get(playlistId).add(track(uri.substring(uri.lastIndexOf(':') + 1)));
        }
    }

    @Override public void removeItems(String playlistId, List<String> uris) {
        record("remove " + playlistId + " " + uris);
        playlists.get(playlistId).removeIf(t -> uris.contains(t.uri()));
    }

    @Override public void reorderItems(String playlistId, int from, int to) {
        record("reorder " + playlistId + " " + from + " " + to);
        List<Track> list = playlists.get(playlistId);
        Track moved = list.remove(from);
        list.add(to > from ? to - 1 : to, moved);
    }

    @Override public List<Track> getPlaylistItems(String playlistId) {
        return List.copyOf(playlists.get(playlistId));
    }

    @Override public void unfollowPlaylist(String playlistId) {
        record("unfollow " + playlistId);
        playlists.remove(playlistId);
    }

    @Override public List<Track> search(String query, int limit) {
        calls.add("search " + query + " " + limit);
        return searchResults.getOrDefault(query, List.of());
    }
}
