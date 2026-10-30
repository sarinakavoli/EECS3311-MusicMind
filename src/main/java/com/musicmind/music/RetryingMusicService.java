package com.musicmind.music;

import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;

import java.util.List;
import java.util.function.Supplier;

/**
 * Decorator (GoF) over a {@link MusicService} that transparently retries calls
 * which fail with HTTP 429 (rate limiting). It honours Spotify's
 * {@code Retry-After} hint and applies exponential backoff, up to a bounded
 * number of attempts. Because it is a decorator, every client keeps depending
 * only on {@link MusicService} and gains resilience for free.
 */
public class RetryingMusicService implements MusicService {

    /** Seam so tests can verify backoff without really sleeping. */
    interface Sleeper {
        void sleepSeconds(long seconds) throws InterruptedException;
    }

    private final MusicService delegate;
    private final int maxRetries;
    private final Sleeper sleeper;

    public RetryingMusicService(MusicService delegate) {
        this(delegate, 3, seconds -> Thread.sleep(seconds * 1000L));
    }

    RetryingMusicService(MusicService delegate, int maxRetries, Sleeper sleeper) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
    }

    private <T> T withRetry(Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (RateLimitException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                long wait = Math.max(e.retryAfterSeconds(), 1L << (attempt - 1)); // 1,2,4,... s
                try {
                    sleeper.sleepSeconds(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new MusicServiceException("Interrupted while backing off from a Spotify rate limit.", ie);
                }
            }
        }
    }

    private void withRetry(Runnable call) {
        withRetry(() -> {
            call.run();
            return null;
        });
    }

    // ---- delegated MusicService methods, each wrapped in retry ----

    @Override
    public List<Track> getTopItems(String type, String timeRange) {
        return withRetry(() -> delegate.getTopItems(type, timeRange));
    }

    @Override
    public List<Artist> getTopArtists(String timeRange) {
        return withRetry(() -> delegate.getTopArtists(timeRange));
    }

    @Override
    public List<Track> getSavedTracks() {
        return withRetry(delegate::getSavedTracks);
    }

    @Override
    public List<Track> getRecentlyPlayed() {
        return withRetry(delegate::getRecentlyPlayed);
    }

    @Override
    public List<Track> search(String query, int limit) {
        return withRetry(() -> delegate.search(query, limit));
    }

    @Override
    public List<Album> getArtistAlbums(String artistId) {
        return withRetry(() -> delegate.getArtistAlbums(artistId));
    }

    @Override
    public List<Track> getAlbumTracks(String albumId) {
        return withRetry(() -> delegate.getAlbumTracks(albumId));
    }

    @Override
    public Playlist createPlaylist(String userId, String name) {
        return withRetry(() -> delegate.createPlaylist(userId, name));
    }

    @Override
    public void addItems(String playlistId, List<String> uris) {
        withRetry(() -> delegate.addItems(playlistId, uris));
    }

    @Override
    public void removeItems(String playlistId, List<String> uris) {
        withRetry(() -> delegate.removeItems(playlistId, uris));
    }

    @Override
    public void reorderItems(String playlistId, int from, int to) {
        withRetry(() -> delegate.reorderItems(playlistId, from, to));
    }

    @Override
    public List<Track> getPlaylistItems(String playlistId) {
        return withRetry(() -> delegate.getPlaylistItems(playlistId));
    }

    @Override
    public String currentUserId() {
        return withRetry(delegate::currentUserId);
    }
}
