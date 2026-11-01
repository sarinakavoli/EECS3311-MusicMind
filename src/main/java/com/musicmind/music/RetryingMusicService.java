package com.musicmind.music;

import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Decorator (GoF) over a {@link MusicService} that transparently retries calls
 * which fail with HTTP 429 (rate limiting) or 5xx (Spotify unavailable). It
 * honours Spotify's {@code Retry-After} hint, otherwise applies exponential
 * backoff, up to a bounded number of attempts. Other 4xx errors (including an
 * expired login) are never retried. A 5xx on a write that would not be safe twice
 * (add, create, reorder) is not retried either: Spotify may have applied it before
 * failing. Because it is a decorator, every client keeps depending only on
 * {@link MusicService}.
 */
public class RetryingMusicService implements MusicService {

    /** Seam so tests can verify backoff without really sleeping. */
    interface Sleeper {
        void sleepSeconds(long seconds) throws InterruptedException;
    }

    private final MusicService delegate;
    private final int maxRetries;
    /** A server asking us to wait longer than this is treated as unavailable. */
    private final long maxWaitSeconds;
    /** Most URIs per add or remove request; each batch is retried on its own. */
    private final int batchSize;
    private final Sleeper sleeper;

    /** Reads the retry count, the longest wait and the batch size from the same {@link Config} as the adapter. */
    public RetryingMusicService(MusicService delegate, Config config) {
        this(delegate, config, seconds -> Thread.sleep(seconds * 1000L));
    }

    RetryingMusicService(MusicService delegate, Config config, Sleeper sleeper) {
        this.delegate = delegate;
        this.maxRetries = config.spotifyRetryCount();
        this.maxWaitSeconds = config.spotifyMaxWaitSeconds();
        this.batchSize = config.spotifyAddBatchSize();
        if (batchSize < 1) {
            throw new IllegalArgumentException("The batch size must be at least 1, got " + batchSize);
        }
        this.sleeper = sleeper;
    }

    private static boolean retryable(MusicServiceException e) {
        return e.statusCode() == 429 || e.statusCode() >= 500;
    }

    <T> T withBackoff(Supplier<T> call) {
        return withBackoff(call, true);
    }

    /** @param repeatable whether sending the call twice does no harm; if not, only a 429 (which was refused) is retried */
    private <T> T withBackoff(Supplier<T> call, boolean repeatable) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (MusicServiceException e) {
                if (!retryable(e)) {
                    throw e;
                }
                if (!repeatable && e.statusCode() != 429) {
                    throw giveUp(e, "Spotify failed during this playlist change (HTTP " + e.statusCode()
                            + "), so it may or may not have gone through. Check the playlist before trying again.");
                }
                if (e.retryAfterSeconds() > maxWaitSeconds) {
                    throw giveUp(e, "Spotify asked us to wait " + e.retryAfterSeconds()
                            + " seconds, which is too long. Please try again later.");
                }
                if (attempt >= maxRetries) {
                    throw giveUp(e, e.statusCode() == 429
                            ? "Spotify rate limit still exceeded after " + maxRetries + " retries. Please wait a moment and try again."
                            : "Spotify is temporarily unavailable (HTTP " + e.statusCode() + ") after "
                                    + maxRetries + " retries. Please try again later.");
                }
                attempt++;
                long wait = Math.max(e.retryAfterSeconds(), 1L << (attempt - 1)); // 1,2,4,... s
                try {
                    sleeper.sleepSeconds(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new MusicServiceException("Interrupted while backing off from a Spotify error.", ie);
                }
            }
        }
    }

    private static MusicServiceException giveUp(MusicServiceException cause, String message) {
        if (cause.statusCode() == 429) {
            return new RateLimitException(message, cause.retryAfterSeconds());
        }
        return new MusicServiceException(message, cause.statusCode(), cause, cause.retryAfterSeconds());
    }

    void withBackoff(Runnable call) {
        withBackoff(() -> {
            call.run();
            return null;
        });
    }

    // ---- delegated MusicService methods, each wrapped in retry ----

    @Override
    public List<Track> getTopItems(String type, String timeRange) {
        return withBackoff(() -> delegate.getTopItems(type, timeRange));
    }

    @Override
    public List<Artist> getTopArtists(String timeRange) {
        return withBackoff(() -> delegate.getTopArtists(timeRange));
    }

    @Override
    public List<Track> getSavedTracks() {
        return withBackoff(delegate::getSavedTracks);
    }

    @Override
    public List<Track> getRecentlyPlayed() {
        return withBackoff(delegate::getRecentlyPlayed);
    }

    @Override
    public List<Track> search(String query, int limit) {
        return withBackoff(() -> delegate.search(query, limit));
    }

    @Override
    public List<Album> getArtistAlbums(String artistId) {
        return withBackoff(() -> delegate.getArtistAlbums(artistId));
    }

    @Override
    public List<Track> getAlbumTracks(String albumId) {
        return withBackoff(() -> delegate.getAlbumTracks(albumId));
    }

    @Override
    public Playlist createPlaylist(String userId, String name) {
        return withBackoff(() -> delegate.createPlaylist(userId, name), false);
    }

    @Override
    public void addItems(String playlistId, List<String> uris) {
        inBatches(uris, false, batch -> delegate.addItems(playlistId, batch));
    }

    @Override
    public void removeItems(String playlistId, List<String> uris) {
        inBatches(uris, true, batch -> delegate.removeItems(playlistId, batch));
    }

    /**
     * Sends the URIs in batches, retrying each batch by itself, so a failure on a later batch never
     * re-sends an earlier one (which would add its tracks twice).
     */
    private void inBatches(List<String> uris, boolean repeatable, Consumer<List<String>> send) {
        for (int i = 0; i < uris.size(); i += batchSize) {
            List<String> batch = uris.subList(i, Math.min(i + batchSize, uris.size()));
            withBackoff(() -> {
                send.accept(batch);
                return null;
            }, repeatable);
        }
    }

    @Override
    public void reorderItems(String playlistId, int from, int to) {
        withBackoff(() -> {
            delegate.reorderItems(playlistId, from, to);
            return null;
        }, false);
    }

    @Override
    public List<Track> getPlaylistItems(String playlistId) {
        return withBackoff(() -> delegate.getPlaylistItems(playlistId));
    }

    @Override
    public void unfollowPlaylist(String playlistId) {
        withBackoff(() -> delegate.unfollowPlaylist(playlistId));
    }

    @Override
    public String currentUserId() {
        return withBackoff(delegate::currentUserId);
    }
}
