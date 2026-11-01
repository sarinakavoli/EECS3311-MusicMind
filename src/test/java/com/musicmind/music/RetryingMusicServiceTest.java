package com.musicmind.music;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** The one Config the service reads its retry count (and batch size) from. */
    private static Config retries(int count) {
        return Config.of(Map.of()).withTunables(Map.of("spotify.retryCount", String.valueOf(count)));
    }

    @Test
    void batchSizeComesFromTheInjectedConfigLikeTheAdapters() {
        List<Integer> sizes = new ArrayList<>();
        MusicService recorder = new StubMusicService() {
            @Override public void addItems(String playlistId, List<String> uris) { sizes.add(uris.size()); }
        };
        Config config = Config.of(Map.of()).withTunables(Map.of("spotify.addBatchSize", "2"));

        new RetryingMusicService(recorder, config, s -> { }).addItems("pl1", uris(5));

        assertEquals(List.of(2, 2, 1), sizes);
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
        RetryingMusicService service = new RetryingMusicService(flaky, retries(3), sleeper);

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
        RetryingMusicService service = new RetryingMusicService(alwaysLimited, retries(2), s -> { });

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

        assertEquals("sarina", new RetryingMusicService(flaky, retries(3), sleeper).currentUserId());

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

        assertThrows(MusicServiceException.class, () -> new RetryingMusicService(notFound, retries(3), sleeper).currentUserId());

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

        assertThrows(AuthExpiredException.class, () -> new RetryingMusicService(expired, retries(3), s -> { }).currentUserId());
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
                () -> new RetryingMusicService(down, retries(2), s -> { }).currentUserId());

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
                () -> new RetryingMusicService(limited, retries(1), s -> { }).currentUserId());

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
                () -> new RetryingMusicService(limited, retries(3), sleeper).currentUserId());

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

        new RetryingMusicService(flaky, retries(3), s -> { }).unfollowPlaylist("pl1");

        assertEquals(2, calls[0]);
    }

    private static List<String> uris(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> "spotify:track:t" + i).toList();
    }

    @Test
    void aRateLimitOnTheSecondBatchResendsOnlyThatBatch() {
        List<List<String>> sent = new ArrayList<>();
        int[] calls = {0};
        MusicService flaky = new StubMusicService() {
            @Override public void addItems(String playlistId, List<String> items) {
                if (++calls[0] == 2) {
                    throw new RateLimitException("429", 1);
                }
                sent.add(items);
            }
        };
        List<String> all = uris(250);

        new RetryingMusicService(flaky, retries(3), s -> { }).addItems("pl1", all);

        assertEquals(4, calls[0], "batch 1, batch 2 (429), batch 2 again, batch 3");
        assertEquals(List.of(all.subList(0, 100), all.subList(100, 200), all.subList(200, 250)), sent,
                "every batch arrived once and none was re-sent");
    }

    @Test
    void removeItemsAlsoRetriesEachBatchOnItsOwn() {
        List<List<String>> sent = new ArrayList<>();
        int[] calls = {0};
        MusicService flaky = new StubMusicService() {
            @Override public void removeItems(String playlistId, List<String> items) {
                if (++calls[0] == 3) {
                    throw new MusicServiceException("down", 503);
                }
                sent.add(items);
            }
        };
        List<String> all = uris(250);

        new RetryingMusicService(flaky, retries(3), s -> { }).removeItems("pl1", all);

        assertEquals(4, calls[0]);
        assertEquals(List.of(all.subList(0, 100), all.subList(100, 200), all.subList(200, 250)), sent);
    }

    @Test
    void aBatchThatKeepsFailingGivesUpWithoutSendingLaterBatches() {
        int[] calls = {0};
        MusicService down = new StubMusicService() {
            @Override public void addItems(String playlistId, List<String> items) {
                calls[0]++;
                throw new RateLimitException("429", 1);
            }
        };

        assertThrows(RateLimitException.class, () -> new RetryingMusicService(down, retries(2), s -> { }).addItems("pl1", uris(250)));

        assertEquals(3, calls[0], "first batch only: one attempt plus two retries");
    }

    @Test
    void aBatchSizeBelowOneIsRejectedInsteadOfLoopingForever() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryingMusicService(new StubMusicService(), Config.of(Map.of()).withTunables(Map.of("spotify.addBatchSize", "0")), s -> { }));
    }

    @Test
    void retriesOverARealAdapterAgainstAFailingServer(@TempDir java.nio.file.Path dir) throws Exception {
        try (FakeSpotifyServer api = new FakeSpotifyServer()) {
            int[] hits = {0};
            api.respond(r -> ++hits[0] == 1
                    ? FakeSpotifyServer.Reply.status(503, "", "Retry-After", "2")
                    : FakeSpotifyServer.Reply.ok("{\"id\":\"sarina\"}"));
            RecordingSleeper sleeper = new RecordingSleeper();

            assertEquals("sarina", new RetryingMusicService(api.adapter(dir), retries(3), sleeper).currentUserId());

            assertEquals(List.of(2L), sleeper.sleeps);
        }
    }
}
