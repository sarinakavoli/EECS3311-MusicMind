package com.musicmind.controller;

import com.musicmind.agent.PreferenceSettings;
import com.musicmind.agent.command.PlaylistChangeException;
import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.config.Config;
import com.musicmind.domain.Album;
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

    /** Plays 5 days ago (two of "a"), 1 day ago ("b"), and 20 days ago ("old", outside the week); newest first. */
    private class Plays extends Music {
        final java.time.Instant now = java.time.Instant.now();
        final List<com.musicmind.domain.Play> plays;
        Plays(List<com.musicmind.domain.Play> plays) { this.plays = plays; }
        @Override public List<com.musicmind.domain.Play> getRecentPlays() { return plays; }
        @Override public List<Artist> withGenres(List<Artist> artists) {
            return artists.stream().map(a -> new Artist(a.id(), a.name(), List.of("genre-" + a.id()))).toList();
        }
    }

    private static com.musicmind.domain.Play play(String id, String artist, java.time.Instant at) {
        return new com.musicmind.domain.Play(
                new Track(id, "spotify:track:" + id, "Title " + id, 1000, false, List.of(artist), List.of("Name " + artist)), at);
    }

    @Test
    void pastWeekCountsPlaysFromTheLastSevenDaysOnly() {
        java.time.Instant now = java.time.Instant.now();
        Music music = new Plays(List.of(
                play("t1", "b", now.minus(java.time.Duration.ofDays(1))),
                play("t2", "a", now.minus(java.time.Duration.ofDays(3))),
                play("t2", "a", now.minus(java.time.Duration.ofDays(5))),
                play("t3", "old", now.minus(java.time.Duration.ofDays(20)))));
        AppController c = controller(music);

        ListeningProfile p = c.analyzeTaste(c.login(), "week");

        assertEquals("week", p.range());
        assertEquals(List.of("a", "b"), p.topArtists().stream().map(Artist::id).toList(), "most played first");
        assertEquals(List.of("t2", "t1"), p.topTracks().stream().map(Track::id).toList());
        assertEquals("Name a", p.topArtists().get(0).name());
        assertTrue(p.genreHistogram().containsKey("genre-a"), "genres come through the genre path");
        assertFalse(p.genreHistogram().containsKey("genre-old"));
        assertTrue(p.notices().stream().anyMatch(n -> n.contains("last 3 plays") && n.contains("50 most recent")),
                p.notices().toString());
        assertEquals("week", c.lastProfile(c.currentUser()).orElseThrow().range(), "derived data only is stored, with the range");
    }

    @Test
    void pastWeekWithNoRecentPlaysSaysSoPlainly() {
        java.time.Instant now = java.time.Instant.now();
        AppController c = controller(new Plays(List.of(play("t3", "old", now.minus(java.time.Duration.ofDays(20))))));

        ListeningProfile p = c.analyzeTaste(c.login(), "week");

        assertTrue(p.isEmpty());
        assertTrue(p.notices().stream().anyMatch(n -> n.contains("No plays in the last 7 days")), p.notices().toString());
    }

    @Test
    void yourTasteOffersPastWeekButTrendsKeepsSpotifysThreeRanges() {
        AppController c = controller(new Music());
        assertEquals(List.of("week", "short", "medium", "long"), c.timeRanges());
        assertEquals(List.of("short", "medium", "long"), c.trendRanges());
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
    void lastfmAttributionComesFromConfiguration() {
        assertEquals(config.lastfmAttribution(), controller(new Music()).lastfmAttribution());
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
    void artistHint_isAnAlbumTitle() {
        Music music = new Music() {
            @Override public List<Album> getArtistAlbums(String id) { return List.of(new Album("al1", "First Light", "2020")); }
        };
        AppController c = controller(music);
        c.login();
        assertEquals("First Light", c.artistHint("other"));
    }

    @Test
    void artistHint_marksArtistsInYourListening() {
        Music music = new Music() {
            @Override public List<Album> getArtistAlbums(String id) { return List.of(new Album("al1", "First Light", "2020")); }
        };
        AppController c = controller(music);
        c.analyzeTaste(c.login());
        assertEquals(config.wording("artistHintListening") + " \u00b7 First Light", c.artistHint("indie0"));
        assertEquals("First Light", c.artistHint("not-in-profile"));
    }

    @Test
    void artistHints_oneLinePerArtist_withoutExtraSpotifyReads() {
        List<String> topReads = new ArrayList<>();
        Music music = new Music() {
            @Override public List<Album> getArtistAlbums(String id) { return List.of(new Album("al1", "First Light", "2020")); }
            @Override public List<Artist> getTopArtists(String range) {
                topReads.add(range);
                return super.getTopArtists(range);
            }
        };
        AppController c = controller(music);
        c.analyzeTaste(c.login());
        int readsBefore = topReads.size();

        Map<String, String> hints = c.artistHints(List.of("indie0", "not-in-profile"));

        assertEquals(config.wording("artistHintListening") + " \u00b7 First Light", hints.get("indie0"));
        assertEquals("First Light", hints.get("not-in-profile"));
        assertEquals(readsBefore, topReads.size(), "hints are display-only: no new listening reads");
    }

    @Test
    void artistHint_isEmptyWhenTheReadFails() {
        Music music = new Music() {
            @Override public List<Album> getArtistAlbums(String id) { throw new RuntimeException("down"); }
        };
        AppController c = controller(music);
        c.login();
        assertEquals("", c.artistHint("other"));
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
    void unblockTrack_removesOnlyThatId() {
        AppController c = controller(new Music());
        String first = "1234567890123456789012";
        String second = "abcdefghijABCDEFGHIJ12";
        c.blockTrack(first);
        c.blockTrack(second);
        c.unblockTrack("spotify:track:" + first);
        assertEquals(Set.of(second), PreferenceSettings.from(c.getPreferences()).blockedTracks());
    }

    @Test
    void unblockTrack_lastIdClearsTheRule() {
        AppController c = controller(new Music());
        String only = "1234567890123456789012";
        c.blockTrack(only);
        c.unblockTrack(only);
        assertTrue(c.getPreferences().stream().noneMatch(p -> PreferenceSettings.BLOCKED_TRACKS.equals(p.key())));
        assertTrue(PreferenceSettings.from(c.getPreferences()).blockedTracks().isEmpty());
    }

    @Test
    void unblockArtist_removesOnlyThatId() {
        AppController c = controller(new Music());
        c.blockArtist(ARTIST_ONE);
        c.blockArtist(ARTIST_TWO);
        c.unblockArtist("spotify:artist:" + ARTIST_ONE);
        assertEquals(Set.of(ARTIST_TWO), PreferenceSettings.from(c.getPreferences()).blockedArtists());
        c.unblockArtist(ARTIST_TWO);
        assertTrue(c.getPreferences().stream().noneMatch(p -> PreferenceSettings.BLOCKED_ARTISTS.equals(p.key())));
    }

    @Test
    void unblock_badIdIsRefused() {
        AppController c = controller(new Music());
        assertThrows(IllegalArgumentException.class, () -> c.unblockArtist("nope"));
        assertThrows(IllegalArgumentException.class, () -> c.unblockTrack("nope"));
    }

    @Test
    void unblock_preferencesUnreadable_savesNothing() {
        Music music = new Music();
        AppController working = controller(music);
        String kept = "1234567890123456789012";
        working.blockTrack(kept);
        working.blockArtist(ARTIST_ONE);
        List<String> changed = new ArrayList<>();
        PreferenceRepository unreadable = new PreferenceRepository(db, music::currentUserId) {
            @Override public List<UserPreference> findByUser(String userId) {
                throw new PersistenceException("down", new SQLException("down"));
            }
            @Override public void save(UserPreference pref) {
                changed.add(pref.key());
                super.save(pref);
            }
            @Override public void delete(String key) {
                changed.add(key);
                super.delete(key);
            }
        };
        AppController c = new AppController(music, new MemoryManager(db, unreadable,
                new FeedbackRepository(db, music::currentUserId), new SessionRepository(db, music::currentUserId),
                new PlaylistHistoryRepository(db, music::currentUserId), new CandidateRepository(db, music::currentUserId)),
                null, config);

        IllegalStateException track = assertThrows(IllegalStateException.class, () -> c.unblockTrack(kept));
        IllegalStateException artist = assertThrows(IllegalStateException.class, () -> c.unblockArtist(ARTIST_ONE));

        assertEquals(config.wording("prefsUnavailable"), track.getMessage());
        assertEquals(config.wording("prefsUnavailable"), artist.getMessage());
        assertTrue(changed.isEmpty(), changed.toString());
        assertEquals(Set.of(kept), PreferenceSettings.from(working.getPreferences()).blockedTracks());
        assertEquals(Set.of(ARTIST_ONE), PreferenceSettings.from(working.getPreferences()).blockedArtists());
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
    void feedbackFor_beforeLogin_isEmptyWhenNothingWasRecorded() {
        AppController c = controller(new Music());
        assertTrue(c.feedbackFor().isEmpty());
        assertNull(c.currentUser(), "reading feedback must not sign in");
    }

    @Test
    void feedbackFor_beforeLogin_showsWhatWasRecorded() {
        AppController c = controller(new Music());
        c.recordFeedback("4uLU6hMCjMI75M1A2tKUQC", Verdict.ACCEPT);

        assertEquals(Map.of("4uLU6hMCjMI75M1A2tKUQC", Verdict.ACCEPT), c.feedbackFor());
        assertNull(c.currentUser(), "reading feedback must not sign in");
    }

    @Test
    void feedbackFor_withoutSpotify_usesTheLocalUser() {
        MemoryManager local = new MemoryManager(db,
                new PreferenceRepository(db, () -> "local"), new FeedbackRepository(db, () -> "local"),
                new SessionRepository(db, () -> "local"), new PlaylistHistoryRepository(db, () -> "local"),
                new CandidateRepository(db, () -> "local"));
        AppController c = new AppController(null, local, null, config);
        c.recordFeedback("4uLU6hMCjMI75M1A2tKUQC", Verdict.REJECT);

        assertEquals(Map.of("4uLU6hMCjMI75M1A2tKUQC", Verdict.REJECT), c.feedbackFor());
    }

    @Test
    void feedbackFor_whenSigningInWouldOpenTheBrowser_isEmptyAndDoesNotAsk() {
        Music music = new Music() {
            @Override public String currentUserId() { throw new IllegalStateException("must not be asked"); }
        };
        AppController c = new AppController(music, memory(music), null, config, null, () -> true);

        assertTrue(c.feedbackFor().isEmpty());
        assertNull(c.currentUser());
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
