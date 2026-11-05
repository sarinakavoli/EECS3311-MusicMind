package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.TagService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackTagFitTest {

    private final Config config = Config.of(Map.of());

    private static Track song(String title) {
        return new Track("id" + title, "spotify:track:" + title, title, 1000, false, List.of("a1"), List.of("Artist"));
    }

    private static TagService tagged(List<String> tags, List<String> asked) {
        return new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> trackTags(String artist, String title) {
                asked.add(title);
                return tags;
            }
        };
    }

    @Test
    void aGenreTagAloneDoesNotMakeASongFit() {
        TrackTagFit fit = new TrackTagFit(tagged(List.of("soul", "r&b", "pop"), new ArrayList<>()), config,
                List.of("slow dance"), m -> { });

        assertEquals(Optional.of(false), fit.fits(song("S")));
    }

    @Test
    void afterTheTimeBudgetNoFurtherSongIsLookedUp() {
        List<String> asked = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        TrackTagFit fit = new TrackTagFit(tagged(List.of("rock"), asked), config, List.of("slow dance"),
                m -> { }, now::get);

        assertTrue(fit.fits(song("One")).isPresent());
        now.addAndGet(5_000_000_000L);
        assertTrue(fit.fits(song("Two")).isPresent());
        now.addAndGet(config.lastfmTrackTagBudgetSeconds() * 1_000_000_000L);
        assertEquals(Optional.empty(), fit.fits(song("Three")));
        assertEquals(List.of("One", "Two"), asked);
        assertTrue(fit.fits(song("One")).isPresent(), "an answer already known is still given");
    }
}
