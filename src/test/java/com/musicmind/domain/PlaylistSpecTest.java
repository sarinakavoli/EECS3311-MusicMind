package com.musicmind.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistSpecTest {

    private final PlaylistSpec spec = new PlaylistSpec(60, false, 0.7, List.of("artist"), "Mix").withBlockedTracks(List.of("track"));

    private static void keepsTheRest(PlaylistSpec copy) {
        assertEquals(List.of("artist"), copy.blockedArtists());
        assertEquals(List.of("track"), copy.blockedTracks());
    }

    @Test
    void everyCopyKeepsTheBlocks() {
        keepsTheRest(spec.withTargetMinutes(30));
        keepsTheRest(spec.withAllowExplicit(true));
        keepsTheRest(spec.withFamiliarRatio(0.2));
        keepsTheRest(spec.withName("Other"));
    }

    @Test
    void aCopyChangesOnlyWhatItNames() {
        assertEquals(30, spec.withTargetMinutes(30).targetMinutes());
        assertTrue(spec.withAllowExplicit(true).allowExplicit());
        assertEquals(0.2, spec.withFamiliarRatio(0.2).familiarRatio());
        assertEquals("Other", spec.withName("Other").name());
        assertEquals(60, spec.targetMinutes());
    }

    @Test
    void aRelaxationKeepsTheBlocks() {
        keepsTheRest(new Relaxation(Relaxation.Kind.SHORTEN_DURATION, 30, "").applyTo(spec));
        keepsTheRest(new Relaxation(Relaxation.Kind.ALLOW_EXPLICIT, 1, "").applyTo(spec));
        keepsTheRest(new Relaxation(Relaxation.Kind.CHANGE_FAMILIAR_RATIO, 0.5, "").applyTo(spec));
    }

    @Test
    void trackAndArtistBlocksAreSeparate() {
        assertTrue(spec.isTrackBlocked("track"));
        assertFalse(spec.isBlocked("track"));
    }
}
