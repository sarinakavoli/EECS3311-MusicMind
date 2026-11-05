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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void aRequestedSeedGenreInTheSongTagsMakesItFit() {
        TrackTagFit soul = new TrackTagFit(tagged(List.of("soul", "female vocalists"), new ArrayList<>()), config,
                List.of("slow dance"), List.of("soul"), m -> { });
        TrackTagFit rock = new TrackTagFit(tagged(List.of("rock"), new ArrayList<>()), config,
                List.of("slow dance"), List.of("soul"), m -> { });

        assertEquals(Optional.of(true), soul.fits(song("S")));
        assertEquals(Optional.of(false), rock.fits(song("S")));
    }

    @Test
    void rAndBSeedGenreMatchesLastFmsRnbTag() {
        TrackTagFit fit = new TrackTagFit(tagged(List.of("rnb"), new ArrayList<>()), config,
                List.of("slow dance"), List.of("r&b"), m -> { });

        assertEquals(Optional.of(true), fit.fits(song("S")));
    }
}
