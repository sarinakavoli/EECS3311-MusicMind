package com.musicmind.music;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.memory.Database;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.GenreRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenreEnrichingMusicServiceTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    /** Scripted genre source that counts the lookups it is asked for. */
    static class FakeGenres implements GenreService {
        final Map<String, Optional<List<String>>> byId = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        @Override public Optional<List<String>> lookup(Artist artist) {
            asked.add(artist.id());
            return byId.getOrDefault(artist.id(), Optional.of(List.of()));
        }
    }

    private static MusicService topArtists(Artist... artists) {
        return new StubMusicService() {
            @Override public List<Artist> getTopArtists(String timeRange) { return List.of(artists); }
        };
    }

    private GenreEnrichingMusicService service(MusicService delegate, FakeGenres genres, Clock clock) {
        return new GenreEnrichingMusicService(delegate, genres, new GenreRepository(db), config, clock);
    }

    private GenreEnrichingMusicService service(MusicService delegate, FakeGenres genres) {
        return service(delegate, genres, Clock.systemUTC());
    }

    @Test
    void artistsWithoutGenresGetThemFilledInOrderKept() {
        FakeGenres genres = new FakeGenres();
        genres.byId.put("a1", Optional.of(List.of("jazz", "bebop")));
        MusicService s = service(topArtists(new Artist("a1", "A", List.of()), new Artist("a2", "B", List.of())), genres);

        List<Artist> out = s.getTopArtists("medium_term");

        assertEquals(List.of("a1", "a2"), out.stream().map(Artist::id).toList());
        assertEquals(List.of("A", "B"), out.stream().map(Artist::name).toList());
        assertEquals(List.of("jazz", "bebop"), out.get(0).genres());
        assertTrue(out.get(1).genres().isEmpty());
    }

    @Test
    void artistsSpotifyAlreadyGaveGenresAreLeftAlone() {
        FakeGenres genres = new FakeGenres();
        Artist known = new Artist("a1", "A", List.of("pop"));
        List<Artist> out = service(topArtists(known), genres).getTopArtists("medium_term");

        assertEquals(List.of("pop"), out.get(0).genres());
        assertTrue(genres.asked.isEmpty());
    }

    @Test
    void aSecondRunIsServedFromTheCacheWithNoLookups() {
        FakeGenres genres = new FakeGenres();
        genres.byId.put("a1", Optional.of(List.of("jazz")));
        MusicService delegate = topArtists(new Artist("a1", "A", List.of()));
        service(delegate, genres).getTopArtists("medium_term");

        List<Artist> again = service(delegate, genres).getTopArtists("medium_term");

        assertEquals(List.of("a1"), genres.asked, "looked up once only");
        assertEquals(List.of("jazz"), again.get(0).genres());
    }

    @Test
    void aNoMatchIsCachedToo() {
        FakeGenres genres = new FakeGenres();
        MusicService delegate = topArtists(new Artist("a1", "A", List.of()));
        service(delegate, genres).getTopArtists("medium_term");
        service(delegate, genres).getTopArtists("medium_term");

        assertEquals(1, genres.asked.size());
    }

    @Test
    void aFailedLookupIsNotCachedSoItIsRetriedNextTime() {
        FakeGenres genres = new FakeGenres();
        genres.byId.put("a1", Optional.empty());
        MusicService delegate = topArtists(new Artist("a1", "A", List.of()));
        service(delegate, genres).getTopArtists("medium_term");
        service(delegate, genres).getTopArtists("medium_term");

        assertEquals(2, genres.asked.size());
    }

    @Test
    void anExpiredCacheEntryIsFetchedAgain() {
        FakeGenres genres = new FakeGenres();
        genres.byId.put("a1", Optional.of(List.of("jazz")));
        MusicService delegate = topArtists(new Artist("a1", "A", List.of()));
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service(delegate, genres, Clock.fixed(start, ZoneOffset.UTC)).getTopArtists("medium_term");

        Instant later = start.plus(Duration.ofDays(config.musicBrainzCacheDays() + 1));
        service(delegate, genres, Clock.fixed(later, ZoneOffset.UTC)).getTopArtists("medium_term");
        assertEquals(2, genres.asked.size());

        Instant fresh = later.plus(Duration.ofDays(config.musicBrainzCacheDays() - 1));
        service(delegate, genres, Clock.fixed(fresh, ZoneOffset.UTC)).getTopArtists("medium_term");
        assertEquals(2, genres.asked.size(), "an entry inside the expiry window is still served from cache");
    }

    @Test
    void aDatabaseOutageJustMeansNoCache() {
        FakeGenres genres = new FakeGenres();
        genres.byId.put("a1", Optional.of(List.of("jazz")));
        Database down = new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        MusicService s = new GenreEnrichingMusicService(topArtists(new Artist("a1", "A", List.of())),
                genres, new GenreRepository(down), config, Clock.systemUTC());

        assertEquals(List.of("jazz"), s.getTopArtists("medium_term").get(0).genres());
        s.getTopArtists("medium_term");
        assertEquals(2, genres.asked.size(), "nothing cached, so asked again, but nothing failed");
    }

    @Test
    void everyOtherMethodIsDelegatedUnchanged() throws Exception {
        List<String> calls = new ArrayList<>();
        Object result = new Object();
        MusicService recorder = (MusicService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MusicService.class}, (proxy, m, args) -> {
                    calls.add(m.getName());
                    return m.getReturnType() == String.class ? "id"
                            : m.getReturnType() == List.class ? List.of(result) : null;
                });
        MusicService s = service(recorder, new FakeGenres());

        int checked = 0;
        for (Method m : MusicService.class.getMethods()) {
            if (m.getName().equals("getTopArtists")) {
                continue;
            }
            Object[] args = new Object[m.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> t = m.getParameterTypes()[i];
                args[i] = t == String.class ? "x" : t == int.class ? 1 : List.of("u");
            }
            calls.clear();
            Object out = m.invoke(s, args);
            assertEquals(List.of(m.getName()), calls, m.getName() + " must reach the delegate once");
            if (m.getReturnType() == List.class) {
                assertSame(result, ((List<?>) out).get(0), m.getName());
            } else if (m.getReturnType() == String.class) {
                assertEquals("id", out, m.getName());
            }
            checked++;
        }
        assertEquals(MusicService.class.getMethods().length - 1, checked);
    }
}
