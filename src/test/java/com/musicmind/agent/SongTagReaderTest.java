package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.SongTags;
import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TagService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SongTagReaderTest {

    private final Config config = Config.of(Map.of());

    private static Track song(int n) {
        return new Track("id" + n, "spotify:track:" + n, "Song " + n, 1000, false, List.of("ar" + n), List.of("Artist " + n));
    }

    private static TagService service(List<String> asked, List<String> tags) {
        return new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> trackTags(String artist, String title) {
                asked.add(title);
                return tags;
            }
        };
    }

    @Test
    void capsLookups_thenArtistTags_thenNone() {
        List<String> asked = new ArrayList<>();
        List<Track> tracks = IntStream.rangeClosed(1, 32).mapToObj(SongTagReaderTest::song).toList();
        ArtistGenreCache cache = id -> id.equals("ar31") ? Optional.of(List.of("indie", "folk")) : Optional.empty();

        Map<String, SongTags> read = new SongTagReader(service(asked, List.of("sad", "mellow")), cache, config,
                System::nanoTime).read(tracks);

        assertEquals(30, asked.size());
        assertEquals(new SongTags(List.of("sad", "mellow"), SongTags.Kind.SONG), read.get("id1"));
        assertEquals(new SongTags(List.of("indie", "folk"), SongTags.Kind.ARTIST), read.get("id31"));
        assertEquals(new SongTags(List.of(), SongTags.Kind.NONE), read.get("id32"));
    }

    @Test
    void stopsAfterFailure() {
        List<String> asked = new ArrayList<>();
        TagService failing = new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> trackTags(String artist, String title) {
                asked.add(title);
                throw new MusicServiceException("Last.fm returned HTTP 500.");
            }
        };
        List<Track> tracks = IntStream.rangeClosed(1, 5).mapToObj(SongTagReaderTest::song).toList();

        Map<String, SongTags> read = new SongTagReader(failing, id -> Optional.of(List.of("rock")), config,
                System::nanoTime).read(tracks);

        assertEquals(List.of("Song 1"), asked);
        assertEquals(SongTags.Kind.ARTIST, read.get("id3").kind());
    }

    @Test
    void timeBudget() {
        List<String> asked = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        TagService clocked = new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> trackTags(String artist, String title) {
                asked.add(title);
                now.addAndGet(config.lastfmTrackTagBudgetSeconds() * 1_000_000_000L / 2 + 1);
                return List.of("calm");
            }
        };
        List<Track> tracks = IntStream.rangeClosed(1, 6).mapToObj(SongTagReaderTest::song).toList();

        Map<String, SongTags> read = new SongTagReader(clocked, ArtistGenreCache.NONE, config, now::get).read(tracks);

        assertEquals(List.of("Song 1", "Song 2"), asked);
        assertEquals(SongTags.Kind.SONG, read.get("id2").kind());
        assertEquals(SongTags.Kind.NONE, read.get("id3").kind());
    }

    @Test
    void keepsAtMostTrackTagsKept() {
        List<String> many = IntStream.range(0, 15).mapToObj(i -> "t" + i).toList();
        Map<String, SongTags> read = new SongTagReader(service(new ArrayList<>(), many), ArtistGenreCache.NONE, config,
                System::nanoTime).read(List.of(song(1)));

        assertEquals(config.lastfmTrackTagsKept(), read.get("id1").tags().size());
        assertTrue(read.get("id1").tags().get(0).equals("t0"));
    }
}
