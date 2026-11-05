package com.musicmind.controller;

import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.config.Config;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.SnapshotPreview;
import com.musicmind.domain.Track;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.CandidateRepository;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.RateLimitException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The restore preview reads titles one song at a time, with a cap, and never writes. */
class PreviewSnapshotTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    private class Music extends StubMusicService {
        final List<String> reads = new ArrayList<>();
        final Map<String, RuntimeException> failures = new java.util.HashMap<>();
        @Override public String currentUserId() { return "sp-user"; }
        @Override public Track getTrack(String id) {
            reads.add(id);
            if (failures.containsKey(id)) throw failures.get(id);
            return new Track(id, "spotify:track:" + id, "Title " + id, 1000, false, List.of(), List.of("Artist"));
        }
    }

    private AppController controller(Music music) {
        MemoryManager memory = new MemoryManager(db,
                new PreferenceRepository(db, music::currentUserId), new FeedbackRepository(db, music::currentUserId),
                new SessionRepository(db, music::currentUserId), new PlaylistHistoryRepository(db, music::currentUserId),
                new CandidateRepository(db, music::currentUserId));
        return new AppController(music, memory, null, config);
    }

    private int save(AppController c, int songs) {
        List<Track> tracks = IntStream.range(0, songs)
                .mapToObj(i -> new Track("t" + i, "spotify:track:t" + i, "", 0, false, List.of())).toList();
        return c.memory().saveSnapshot(new Playlist("p", "Mix", tracks));
    }

    private static List<String> titles(SnapshotPreview p) {
        return p.songs().stream().map(Track::title).toList();
    }

    @Test
    void success_everySongIsTitled() {
        Music music = new Music();
        AppController c = controller(music);
        c.login();
        SnapshotPreview p = c.previewSnapshot(save(c, 3));

        assertEquals(List.of("Title t0", "Title t1", "Title t2"), titles(p));
        assertEquals(0, p.moreCount());
        assertFalse(p.stoppedEarly());
    }

    @Test
    void oneSongNotFound_theLaterSongsAreStillTitled() {
        Music music = new Music();
        music.failures.put("t1", new MusicServiceException("gone", 404));
        AppController c = controller(music);
        c.login();
        SnapshotPreview p = c.previewSnapshot(save(c, 4));

        assertEquals(List.of("Title t0", "", "Title t2", "Title t3"), titles(p));
        assertFalse(p.stoppedEarly());
    }

    @Test
    void rateLimit_stopsTheLookupsAndSaysSo() {
        Music music = new Music();
        music.failures.put("t1", new RateLimitException("slow down", 30));
        AppController c = controller(music);
        c.login();
        SnapshotPreview p = c.previewSnapshot(save(c, 4));

        assertEquals(List.of("Title t0", "", "", ""), titles(p));
        assertTrue(p.stoppedEarly());
        assertEquals(List.of("t0", "t1"), music.reads, "no more calls once Spotify says to wait");
    }

    @Test
    void theCap_limitsLookupsAndCountsTheRest() {
        Music music = new Music();
        AppController c = controller(music);
        c.login();
        int limit = config.historyPreviewTitleLimit();
        SnapshotPreview p = c.previewSnapshot(save(c, limit + 7));

        assertEquals(limit, p.songs().size());
        assertEquals(limit, music.reads.size());
        assertEquals(7, p.moreCount());
    }

    @Test
    void unknownSnapshot_isRefused() {
        Music music = new Music();
        AppController c = controller(music);
        c.login();
        assertThrows(PlaylistChangeException.class, () -> c.previewSnapshot(9999));
        assertTrue(music.reads.isEmpty());
    }

    @Test
    void previewWritesNothing() {
        Music music = new Music();   // every write method of the stub throws, so a write would fail the test
        AppController c = controller(music);
        c.login();
        int id = save(c, 3);
        int before = c.memory().loadSnapshotSummaries("sp-user").size();
        int sessions = c.memory().loadHistory("sp-user").size();

        c.previewSnapshot(id);

        assertEquals(before, c.memory().loadSnapshotSummaries("sp-user").size());
        assertEquals(sessions, c.memory().loadHistory("sp-user").size());
    }
}
