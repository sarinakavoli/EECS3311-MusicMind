package com.musicmind.music;

import com.musicmind.domain.Artist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the 429 and 5xx retry/backoff behaviour of the RetryingMusicService decorator. */
class RetryingMusicServiceTest {

    /** Records requested sleeps instead of actually sleeping. */
    private static class RecordingSleeper implements RetryingMusicService.Sleeper {
        final List<Long> sleeps = new ArrayList<>();
        @Override public void sleepSeconds(long seconds) { sleeps.add(seconds); }
    }

    @Test
    void retriesAfterRateLimitThenSucceeds() {
        int[] calls = {0};
        MusicService flaky = new StubMusicService() {
            @Override public List<Artist> getTopArtists(String timeRange) {
                calls[0]++;
                if (calls[0] < 3) {
                    throw new RateLimitException("429", 2);
                }
                return List.of(new Artist("1", "Artist", List.of("pop")));
            }
        };
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingMusicService service = new RetryingMusicService(flaky, 3, sleeper);

        List<Artist> result = service.getTopArtists("medium_term");

        assertEquals(1, result.size());
        assertEquals(3, calls[0], "should call through three times (two 429s, then success)");
        assertEquals(2, sleeper.sleeps.size(), "should back off once per retry");
        assertTrue(sleeper.sleeps.get(0) >= 2, "first backoff should honour Retry-After of 2s");
    }

    @Test
    void givesUpAfterMaxRetries() {
        int[] calls = {0};
        MusicService alwaysLimited = new StubMusicService() {
            @Override public String currentUserId() {
                calls[0]++;
                throw new RateLimitException("429", 1);
            }
        };
        RetryingMusicService service = new RetryingMusicService(alwaysLimited, 2, s -> { });

        assertThrows(RateLimitException.class, service::currentUserId);
        assertEquals(3, calls[0], "initial attempt plus two retries");
    }

    @Test
    void retriesServerErrorsHonouringRetryAfter() {
        int[] calls = {0};
        MusicService flaky = new StubMusicService() {
            @Override public String currentUserId() {
                if (++calls[0] == 1) {
                    throw new MusicServiceException("down", 503, null, 9);
                }
                return "sarina";
            }
        };
        RecordingSleeper sleeper = new RecordingSleeper();

        assertEquals("sarina", new RetryingMusicService(flaky, 3, sleeper).currentUserId());

        assertEquals(List.of(9L), sleeper.sleeps);
    }

    @Test
    void neverRetriesClientErrorsOtherThan429() {
        int[] calls = {0};
        MusicService notFound = new StubMusicService() {
            @Override public String currentUserId() {
                calls[0]++;
                throw new MusicServiceException("nope", 404);
            }
        };
        RecordingSleeper sleeper = new RecordingSleeper();

        assertThrows(MusicServiceException.class, () -> new RetryingMusicService(notFound, 3, sleeper).currentUserId());

        assertEquals(1, calls[0]);
        assertTrue(sleeper.sleeps.isEmpty());
    }

    @Test
    void authExpiredIsNotRetried() {
        int[] calls = {0};
        MusicService expired = new StubMusicService() {
            @Override public String currentUserId() {
                calls[0]++;
                throw new AuthExpiredException("Please log in again.");
            }
        };

        assertThrows(AuthExpiredException.class, () -> new RetryingMusicService(expired, 3, s -> { }).currentUserId());
        assertEquals(1, calls[0]);
    }

    @Test
    void persistentServerErrorSurfacesAClearMessageAndStatus() {
        MusicService down = new StubMusicService() {
            @Override public String currentUserId() {
                throw new MusicServiceException("HTTP 502", 502);
            }
        };

        MusicServiceException e = assertThrows(MusicServiceException.class,
                () -> new RetryingMusicService(down, 2, s -> { }).currentUserId());

        assertEquals(502, e.statusCode());
        assertTrue(e.getMessage().contains("temporarily unavailable"), e.getMessage());
        assertTrue(e.getMessage().contains("2 retries"), e.getMessage());
    }

    @Test
    void persistentRateLimitSurfacesAClearMessage() {
        MusicService limited = new StubMusicService() {
            @Override public String currentUserId() {
                throw new RateLimitException("429", 1);
            }
        };

        RateLimitException e = assertThrows(RateLimitException.class,
                () -> new RetryingMusicService(limited, 1, s -> { }).currentUserId());

        assertTrue(e.getMessage().contains("rate limit"), e.getMessage());
    }

    @Test
    void refusesToWaitLongerThanTheCapAndSaysSo() {
        MusicService limited = new StubMusicService() {
            @Override public String currentUserId() {
                throw new RateLimitException("429", 3600);
            }
        };
        RecordingSleeper sleeper = new RecordingSleeper();

        RateLimitException e = assertThrows(RateLimitException.class,
                () -> new RetryingMusicService(limited, 3, sleeper).currentUserId());

        assertTrue(sleeper.sleeps.isEmpty());
        assertTrue(e.getMessage().contains("3600"), e.getMessage());
    }

    @Test
    void wrapsEveryMethodIncludingUnfollowPlaylist() {
        int[] calls = {0};
        MusicService flaky = new StubMusicService() {
            @Override public void unfollowPlaylist(String playlistId) {
                if (++calls[0] == 1) {
                    throw new RateLimitException("429", 1);
                }
            }
        };

        new RetryingMusicService(flaky, 3, s -> { }).unfollowPlaylist("pl1");

        assertEquals(2, calls[0]);
    }

    @Test
    void retriesOverARealAdapterAgainstAFailingServer(@TempDir java.nio.file.Path dir) throws Exception {
        try (FakeSpotifyServer api = new FakeSpotifyServer()) {
            int[] hits = {0};
            api.respond(r -> ++hits[0] == 1
                    ? FakeSpotifyServer.Reply.status(503, "", "Retry-After", "2")
                    : FakeSpotifyServer.Reply.ok("{\"id\":\"sarina\"}"));
            RecordingSleeper sleeper = new RecordingSleeper();

            assertEquals("sarina", new RetryingMusicService(api.adapter(dir), 3, sleeper).currentUserId());

            assertEquals(List.of(2L), sleeper.sleeps);
        }
    }
}
