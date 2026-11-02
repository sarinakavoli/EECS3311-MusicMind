package com.musicmind.agent.state;

import com.musicmind.domain.PlaylistSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRecordTest {

    @Test
    void aStoredSpecKeepsItsBlockedTracks() {
        PlaylistSpec spec = new PlaylistSpec(60, true, 0.5, List.of("artist"), "Mix").withBlockedTracks(List.of("track"));

        PlaylistSpec read = SessionRecord.specOf(SessionRecord.derivedData(spec, List.of("a", "b"))).orElseThrow();

        assertEquals(List.of("track"), read.blockedTracks());
        assertEquals(List.of("artist"), read.blockedArtists());
    }

    @Test
    void aSpecStoredBeforeBlockedTracksLoadsWithNone() {
        String old = "{\"spec\":{\"targetMinutes\":60,\"allowExplicit\":true,\"familiarRatio\":0.5,\"blockedArtists\":[],\"name\":\"Mix\"}}";

        assertTrue(SessionRecord.specOf(old).orElseThrow().blockedTracks().isEmpty());
    }
}
