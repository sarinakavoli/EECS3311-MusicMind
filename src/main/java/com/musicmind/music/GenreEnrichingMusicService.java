package com.musicmind.music;

import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.Track;
import com.musicmind.memory.PersistenceException;
import com.musicmind.memory.repository.GenreRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decorator (GoF) over a {@link MusicService} that fills in artist genres, which
 * Spotify does not return to this app, from a {@link GenreService}. Everything
 * else is delegated untouched. Results (including "no match") are cached by
 * artist id so repeat runs make no lookups; a failed lookup is not cached, and a
 * database outage only means no caching.
 */
public class GenreEnrichingMusicService implements MusicService {

    private final MusicService delegate;
    private final GenreService genres;
    private final GenreRepository cache;
    private final Duration cacheLife;
    private final Clock clock;

    public GenreEnrichingMusicService(MusicService delegate, GenreService genres, GenreRepository cache,
                                      Config config) {
        this(delegate, genres, cache, config, Clock.systemUTC());
    }

    GenreEnrichingMusicService(MusicService delegate, GenreService genres, GenreRepository cache,
                               Config config, Clock clock) {
        this.delegate = delegate;
        this.genres = genres;
        this.cache = cache;
        this.cacheLife = Duration.ofDays(config.musicBrainzCacheDays());
        this.clock = clock;
    }

    @Override
    public List<Artist> getTopArtists(String timeRange) {
        List<Artist> enriched = new ArrayList<>();
        for (Artist artist : delegate.getTopArtists(timeRange)) {
            enriched.add(artist.genres().isEmpty() ? withGenres(artist) : artist);
        }
        return enriched;
    }

    private Artist withGenres(Artist artist) {
        Instant now = clock.instant();
        Optional<List<String>> cached = Optional.empty();
        try {
            cached = cache.find(artist.id(), now.minus(cacheLife));
        } catch (PersistenceException ignored) {
            // no database, no cache
        }
        Optional<List<String>> found = cached.isPresent() ? cached : genres.lookup(artist);
        if (cached.isEmpty() && found.isPresent()) {
            try {
                cache.save(artist.id(), found.get(), now);
            } catch (PersistenceException ignored) {
                // not caching is harmless
            }
        }
        return new Artist(artist.id(), artist.name(), found.orElse(List.of()));
    }

    // ---- everything else is delegated ----

    @Override
    public List<Track> getTopItems(String type, String timeRange) {
        return delegate.getTopItems(type, timeRange);
    }

    @Override
    public List<Track> getSavedTracks() {
        return delegate.getSavedTracks();
    }

    @Override
    public List<Track> getRecentlyPlayed() {
        return delegate.getRecentlyPlayed();
    }

    @Override
    public List<Track> search(String query, int limit) {
        return delegate.search(query, limit);
    }

    @Override
    public List<Artist> searchArtists(String query, int limit) {
        return delegate.searchArtists(query, limit);
    }

    @Override
    public Artist getArtist(String artistId) {
        return delegate.getArtist(artistId);
    }

    @Override
    public Track getTrack(String trackId) {
        return delegate.getTrack(trackId);
    }

    @Override
    public List<Album> getArtistAlbums(String artistId) {
        return delegate.getArtistAlbums(artistId);
    }

    @Override
    public List<Track> getAlbumTracks(String albumId) {
        return delegate.getAlbumTracks(albumId);
    }

    @Override
    public Playlist createPlaylist(String userId, String name) {
        return delegate.createPlaylist(userId, name);
    }

    @Override
    public void addItems(String playlistId, List<String> uris) {
        delegate.addItems(playlistId, uris);
    }

    @Override
    public void removeItems(String playlistId, List<String> uris) {
        delegate.removeItems(playlistId, uris);
    }

    @Override
    public void reorderItems(String playlistId, int from, int to) {
        delegate.reorderItems(playlistId, from, to);
    }

    @Override
    public List<Track> getPlaylistItems(String playlistId) {
        return delegate.getPlaylistItems(playlistId);
    }

    @Override
    public void unfollowPlaylist(String playlistId) {
        delegate.unfollowPlaylist(playlistId);
    }

    @Override
    public List<PlaylistSummary> getUserPlaylists() {
        return delegate.getUserPlaylists();
    }

    @Override
    public String currentUserId() {
        return delegate.currentUserId();
    }
}
