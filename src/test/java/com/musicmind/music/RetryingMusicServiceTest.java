package com.musicmind.music;

import com.musicmind.domain.Artist;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the 429 retry/backoff behaviour of the RetryingMusicService decorator. */
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
}
