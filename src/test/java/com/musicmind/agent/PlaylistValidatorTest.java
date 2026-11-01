package com.musicmind.agent;

import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;
import com.musicmind.domain.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistValidatorTest {

    private final PlaylistValidator validator = new PlaylistValidator();

    private Track track(String id, int ms, boolean explicit, String... artistIds) {
        return new Track(id, "spotify:track:" + id, "Track " + id, ms, explicit, List.of(artistIds));
    }

    @Test
    void cleanPlaylistPasses() {
        PlaylistSpec spec = new PlaylistSpec(10, false, 0.7, List.of(), "Study");
        List<Track> tracks = List.of(track("a", 300_000, false), track("b", 300_000, false));
        ValidationResult r = validator.validate(tracks, spec);
        assertTrue(r.isPassed());
        assertTrue(r.violations().isEmpty());
    }

    @Test
    void explicitTrackIsRejectedWhenNotAllowed() {
        PlaylistSpec spec = new PlaylistSpec(0, false, 0.7, List.of(), "Clean");
        ValidationResult r = validator.validate(List.of(track("a", 200_000, true)), spec);
        assertFalse(r.isPassed());
        assertEquals(1, r.violations().size());
    }

    @Test
    void duplicatesAreDetected() {
        PlaylistSpec spec = new PlaylistSpec(0, true, 0.5, List.of(), "Dupes");
        ValidationResult r = validator.validate(List.of(track("a", 1000, false), track("a", 1000, false)), spec);
        assertFalse(r.isPassed());
    }

    @Test
    void blockedArtistIsDetected() {
        PlaylistSpec spec = new PlaylistSpec(0, true, 0.5, List.of("blockedArtist"), "Blocked");
        ValidationResult r = validator.validate(List.of(track("a", 1000, false, "blockedArtist")), spec);
        assertFalse(r.isPassed());
    }

    @Test
    void durationOutsideToleranceFails() {
        PlaylistSpec spec = new PlaylistSpec(90, true, 0.5, List.of(), "Long");
        // only ~5 minutes against a 90-minute target
        ValidationResult r = validator.validate(List.of(track("a", 300_000, false)), spec);
        assertFalse(r.isPassed());
    }

    @Test
    void durationStatusSaysWhichWayTheLengthMissesAndAgreesWithValidate() {
        PlaylistSpec spec = new PlaylistSpec(10, true, 0.5, List.of(), "x");
        List<Track> shortList = List.of(track("a", 60_000, false));
        List<Track> within = List.of(track("a", 600_000, false));
        List<Track> longList = List.of(track("a", 1_200_000, false));

        assertEquals(PlaylistValidator.DurationStatus.TOO_SHORT, validator.durationStatus(shortList, spec));
        assertEquals(PlaylistValidator.DurationStatus.WITHIN, validator.durationStatus(within, spec));
        assertEquals(PlaylistValidator.DurationStatus.TOO_LONG, validator.durationStatus(longList, spec));
        assertTrue(validator.validate(within, spec).passed());
        assertFalse(validator.validate(longList, spec).passed());
    }
}
