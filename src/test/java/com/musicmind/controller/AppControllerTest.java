package com.musicmind.controller;

import com.musicmind.agent.PreferenceSettings;
import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.CandidateRepository;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import com.musicmind.domain.Verdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppControllerTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    /** Spotify fake whose user id is only known after "login", like the real adapter. */
    private class Music extends StubMusicService {
        @Override public String currentUserId() { return "sp-user"; }
        @Override public List<Artist> getTopArtists(String r) {
            String genre = r.startsWith("short") ? "synthwave" : "indie";
            return IntStream.range(0, 6).mapToObj(i -> new Artist(genre + i, "N" + i, List.of(genre))).toList();
        }
        @Override public List<Track> getTopItems(String type, String r) {
            return IntStream.range(0, 6)
                    .mapToObj(i -> new Track("t" + i, "spotify:track:t" + i, "T" + i, 1, false, List.of())).toList();
        }
        @Override public List<Track> getSavedTracks() { return List.of(); }
    }

    private MemoryManager memory(Music music) {
        // Same wiring as AppController.create(): repositories stamp the Spotify user id.
        return new MemoryManager(db,
                new PreferenceRepository(db, music::currentUserId),
                new FeedbackRepository(db, music::currentUserId),
                new SessionRepository(db, music::currentUserId),
                new PlaylistHistoryRepository(db, music::currentUserId),
                new CandidateRepository(db, music::currentUserId));
    }

    private AppController controller(Music music) {
        return new AppController(music, memory(music), null, config);
    }

    @Test
    void worksWithNoLlmKeyAndLabelsTheComputedSummary() {
        AppController c = controller(new Music());
        assertFalse(c.isLlmConfigured());

        ListeningProfile p = c.analyzeTaste(c.login());

        assertTrue(p.summary().endsWith(config.computedLabel()), p.summary());
        TrendReport t = c.analyzeTrends(c.currentUser());
        assertFalse(t.addedGenres().isEmpty(), "the ranges differ, so there is something to narrate");
        assertTrue(t.narrative().endsWith(config.computedLabel()), t.narrative());
    }

    @Test
    void loginReturnsTheSpotifyUserAndCurrentUserRemembersIt() {
        AppController c = controller(new Music());
        assertNull(c.currentUser(), "nobody is logged in yet");

        User u = c.login();

        assertEquals("sp-user", u.id());
        assertEquals("sp-user", u.spotifyUserId());
        assertEquals(u.id(), c.currentUser().id());
    }

    @Test
    void sessionsAreStoredUnderTheLoggedInUserAndLastProfileFindsThem() {
        AppController c = controller(new Music());
        User u = c.login();
        assertTrue(c.lastProfile(u).isEmpty());

        ListeningProfile built = c.analyzeTaste(u);

        assertEquals(1, c.memory().loadHistory("sp-user").size());
        assertEquals(built.genreHistogram(), c.lastProfile(u).orElseThrow().genreHistogram());
        assertTrue(c.lastProfile(new User("other", "o", "other")).isEmpty());
    }

    @Test
    void analyzeTasteWithARangeUsesThatRange() {
        AppController c = controller(new Music());
        ListeningProfile p = c.analyzeTaste(c.login(), "long");
        assertFalse(p.genreHistogram().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> c.analyzeTaste(c.currentUser(), "nope"));
    }

    @Test
    void withoutSpotifyEveryFeatureSaysSoInsteadOfFailingObscurely() {
        AppController c = new AppController(null, memory(new Music()), null, config);
        assertFalse(c.isSpotifyConfigured());
        IllegalStateException e = assertThrows(IllegalStateException.class, c::login);
        assertTrue(e.getMessage().contains("SPOTIFY_CLIENT_ID"), e.getMessage());
        assertNull(c.currentUser());
    }

    @Test
    void spotifyAttributionComesFromConfiguration() {
        assertEquals(config.spotifyAttribution(), controller(new Music()).spotifyAttribution());
    }

    @Test
    void searchWorksWithNoLlmAndExposesTheLabelAndNotice() {
        Music music = new Music() {
            @Override public List<Track> search(String q, int limit) {
                return q.equals("nothing") ? List.of()
                        : List.of(new Track("s1", "spotify:track:s1", "Jazz", 1, false, List.of()));
            }
        };
        AppController c = controller(music);

        assertEquals(1, c.searchMusic("jazz").size());
        assertEquals(config.searchFallbackLabel(), c.lastSearchLabel());
        assertNull(c.lastSearchNotice());

        assertTrue(c.searchMusic("nothing").isEmpty());
        assertTrue(c.lastSearchNotice().contains("nothing"));
    }

    private static Artist artist(String id, String name) {
        return new Artist(id, name, List.of());
    }

    @Test
    void savedFamiliarBias_readsStoredValue() {
        AppController c = controller(new Music());
        c.setPreference(c.parsePreference("familiarBias", "0.3"));
        assertEquals(0.3, c.savedFamiliarBias().getAsDouble(), 1e-9);
    }

    @Test
    void savedFamiliarBias_emptyWhenUnset() {
        assertTrue(controller(new Music()).savedFamiliarBias().isEmpty());
    }

    @Test
    void findArtists_listsEverySameName() {
        Music music = new Music() {
            @Override public List<Artist> searchArtists(String q, int limit) {
                return List.of(artist("a1", "Other Band"), artist("a2", "nova"), artist("a3", "Nova"));
            }
        };
        List<Artist> found = controller(music).findArtists("Nova");
        assertEquals(List.of("a2", "a3"), found.stream().limit(2).map(Artist::id).toList());
        assertEquals(3, found.size());
    }

    @Test
    void findArtists_blankNameIsRefused() {
        AppController c = controller(new Music());
        for (String blank : new String[] {null, "", "   "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.findArtists(blank));
            assertEquals(config.wording("prefsArtistBlank"), e.getMessage());
        }
    }

    @Test
    void findTracks_isTheRankedSearch() {
        Music music = new Music() {
            @Override public List<Track> search(String q, int limit) {
                return List.of(new Track("s1", "spotify:track:s1", "Jazz Night", 1, false, List.of()),
                        new Track("s2", "spotify:track:s2", "Blue Jazz", 1, false, List.of()));
            }
        };
        AppController c = controller(music);

        List<Track> found = c.findTracks("jazz");

        assertEquals(List.of("s1", "s2"), found.stream().map(Track::id).toList());
        assertEquals(c.searchMusic("jazz"), found);
    }

    @Test
    void blockTrack_keepsExistingBlocks() {
        AppController c = controller(new Music());
        String first = "1234567890123456789012";
        String second = "abcdefghijABCDEFGHIJ12";
        c.blockTrack(first);
        c.blockTrack("spotify:track:" + second);
        assertEquals(Set.of(first, second),
                PreferenceSettings.from(c.getPreferences()).blockedTracks());
    }

    @Test
    void blockArtist_addsChosenId() {
        AppController c = controller(new Music());
        c.blockArtist(ARTIST_ONE);
        c.blockArtist(ARTIST_TWO);
        assertEquals(Set.of(ARTIST_ONE, ARTIST_TWO), PreferenceSettings.from(c.getPreferences()).blockedArtists());
    }

    private static final String ARTIST_ONE = "0TnOYISbd1XYRBk9myaseg";
    private static final String ARTIST_TWO = "1vCWHaC5f2uS3yhpwWbIA6";

    @Test
    void blockArtist_rejectsBadId() {
        AppController c = controller(new Music());
        for (String bad : new String[] {null, " ", "artistOne", "spotify:track:" + ARTIST_ONE, ARTIST_ONE + "x", "a,b"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.blockArtist(bad), bad);
            assertEquals(config.wording("prefsBadArtistId"), e.getMessage());
        }
        assertTrue(c.getPreferences().isEmpty(), "nothing was saved");
    }

    @Test
    void blockArtist_acceptsUri() {
        AppController c = controller(new Music());
        c.blockArtist("spotify:artist:" + ARTIST_ONE);
        c.blockArtist(ARTIST_ONE);
        assertEquals(Set.of(ARTIST_ONE), PreferenceSettings.from(c.getPreferences()).blockedArtists());
    }

    @Test
    void blockTrack_preferencesUnreadable_savesNothing() {
        Music music = new Music();
        AppController working = controller(music);
        String kept = "1234567890123456789012";
        working.blockTrack(kept);
        List<String> saved = new ArrayList<>();
        PreferenceRepository unreadable = new PreferenceRepository(db, music::currentUserId) {
            @Override public List<UserPreference> findByUser(String userId) {
                throw new PersistenceException("down", new SQLException("down"));
            }
            @Override public void save(UserPreference pref) {
                saved.add(pref.key());
                super.save(pref);
            }
        };
        AppController c = new AppController(music, new MemoryManager(db, unreadable,
                new FeedbackRepository(db, music::currentUserId), new SessionRepository(db, music::currentUserId),
                new PlaylistHistoryRepository(db, music::currentUserId), new CandidateRepository(db, music::currentUserId)),
                null, config);

        IllegalStateException track = assertThrows(IllegalStateException.class,
                () -> c.blockTrack("abcdefghijABCDEFGHIJ12"));
        IllegalStateException artist = assertThrows(IllegalStateException.class, () -> c.blockArtist(ARTIST_ONE));

        assertEquals(config.wording("prefsUnavailable"), track.getMessage());
        assertEquals(config.wording("prefsUnavailable"), artist.getMessage());
        assertTrue(saved.isEmpty(), saved.toString());
        assertEquals(Set.of(kept), PreferenceSettings.from(working.getPreferences()).blockedTracks(),
                "the blocks already stored are untouched");
    }

    @Test
    void blockTrack_rejectsBadId() {
        AppController c = controller(new Music());
        assertThrows(IllegalArgumentException.class, () -> c.blockTrack("nope"));
        assertTrue(c.getPreferences().isEmpty());
    }

    /** A Spotify fake holding playlists, so delete and the restore that follows can be played through. */
    private class PlaylistMusic extends Music {
        final Map<String, List<Track>> held = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> owned = new ArrayList<>();
        int next = 1;

        void own(String id, String... trackIds) {
            held.put(id, new ArrayList<>(Arrays.stream(trackIds).map(this::track).toList()));
            owned.add(id);
        }
        Track track(String id) { return new Track(id, "spotify:track:" + id, "T" + id, 1000, false, List.of()); }
        @Override public List<PlaylistSummary> getUserPlaylists() {
            return owned.stream().map(id -> new PlaylistSummary(id, "Mix " + id, held.get(id).size())).toList();
        }
        @Override public List<Track> getPlaylistItems(String id) {
            if (!held.containsKey(id)) {
                throw new MusicServiceException("gone", 404);
            }
            return List.copyOf(held.get(id));
        }
        @Override public void unfollowPlaylist(String id) { calls.add("unfollow " + id); held.remove(id); owned.remove(id); }
        @Override public Playlist createPlaylist(String userId, String name) {
            String id = "new" + next++;
            held.put(id, new ArrayList<>());
            owned.add(id);
            return new Playlist(id, name, List.of());
        }
        @Override public void addItems(String id, List<String> uris) {
            uris.forEach(u -> held.get(id).add(track(u.substring(u.lastIndexOf(':') + 1))));
        }
    }

    @Test
    void delete_notOwner_refused() {
        PlaylistMusic music = new PlaylistMusic();
        music.held.put("theirs", new ArrayList<>(List.of(music.track("a"))));   // not in the user's own list
        AppController c = controller(music);
        c.login();

        PlaylistChangeException e = assertThrows(
                PlaylistChangeException.class, () -> c.deletePlaylist("theirs"));

        assertEquals(String.format(config.wording("deleteNotOwner"), "theirs"), e.getMessage());
        assertTrue(music.calls.isEmpty(), music.calls.toString());
        assertTrue(c.memory().loadSnapshotSummaries("sp-user").isEmpty(), "nothing was saved either");
    }

    @Test
    void delete_snapshotCannotBeSaved_playlistIsKept() {
        PlaylistMusic music = new PlaylistMusic();
        music.own("p", "a", "b");
        Database dead = new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        AppController c = new AppController(music, new MemoryManager(dead,
                new PreferenceRepository(dead, music::currentUserId), new FeedbackRepository(dead, music::currentUserId),
                new SessionRepository(dead, music::currentUserId), new PlaylistHistoryRepository(dead, music::currentUserId),
                new CandidateRepository(dead, music::currentUserId)), null, config);
        c.login();

        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> c.deletePlaylist("p"));

        assertEquals(config.wording("snapshotSaveFailed"), e.getMessage());
        assertTrue(music.calls.isEmpty(), "nothing is deleted without a saved copy: " + music.calls);
        assertTrue(music.held.containsKey("p"));
    }

    @Test
    void delete_canBeRecreatedFromSnapshot() {
        PlaylistMusic music = new PlaylistMusic();
        music.own("p", "a", "b", "c");
        AppController c = controller(music);
        c.login();

        int snapshotId = c.deletePlaylist("p");

        assertEquals(List.of("unfollow p"), music.calls);
        assertThrows(PlaylistMissingException.class, () -> c.restore(snapshotId));
        Playlist back = c.recreateFromSnapshot(snapshotId);
        assertEquals("Mix p", back.name());
        assertEquals(List.of("a", "b", "c"), music.held.get(back.spotifyId()).stream().map(Track::id).toList());
    }

    @Test
    void feedbackFor_beforeLogin_isEmpty() {
        AppController c = controller(new Music());
        assertTrue(c.feedbackFor().isEmpty());
        assertNull(c.currentUser(), "reading feedback must not sign in");
    }

    @Test
    void feedbackFor_returnsLatestVerdictPerTrack() {
        AppController c = controller(new Music());
        c.login();
        c.recordFeedback("4uLU6hMCjMI75M1A2tKUQC", Verdict.ACCEPT);
        c.recordFeedback("1301WleyT98MSxVHPtVN9C", Verdict.REJECT);
        c.recordFeedback("4uLU6hMCjMI75M1A2tKUQC", Verdict.REJECT);

        assertEquals(Map.of("4uLU6hMCjMI75M1A2tKUQC", Verdict.REJECT, "1301WleyT98MSxVHPtVN9C", Verdict.REJECT), c.feedbackFor());
    }

    @Test
    void feedbackFor_databaseDown_isEmpty() {
        Music music = new Music();
        Database dead = new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        AppController c = new AppController(music, new MemoryManager(dead,
                new PreferenceRepository(dead, music::currentUserId), new FeedbackRepository(dead, music::currentUserId),
                new SessionRepository(dead, music::currentUserId), new PlaylistHistoryRepository(dead, music::currentUserId),
                new CandidateRepository(dead, music::currentUserId)), null, config);
        c.login();
        assertTrue(c.feedbackFor().isEmpty());
    }
}
