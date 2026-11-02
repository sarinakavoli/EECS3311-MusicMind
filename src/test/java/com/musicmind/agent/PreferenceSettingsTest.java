package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.UserPreference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferenceSettingsTest {

    private static final Config CONFIG = Config.of(java.util.Map.of());

    private static UserPreference parse(String key, String value) {
        return PreferenceSettings.parse(key, value, CONFIG);
    }

    private static PlaylistSpec spec(boolean explicit, double ratio, List<String> blocked) {
        return new PlaylistSpec(0, explicit, ratio, blocked, "n");
    }

    @Test
    void parseBuildsEachKindOfPreference() {
        UserPreference blocked = parse("blockedArtists", " a1, spotify:artist:a2 ,,a1");
        assertEquals("blockedArtists", blocked.key());
        assertEquals("a1,a2", blocked.value());

        UserPreference explicit = parse("explicitPolicy", "Block");
        assertFalse(explicit.explicitAllowed());
        assertTrue(parse("explicitPolicy", "allow").explicitAllowed());

        UserPreference bias = parse("familiarBias", "0.8");
        assertEquals(0.8, bias.familiarBias(), 1e-9);

        assertEquals("indie,jazz", parse("preferredStyles", "jazz, indie").value());
    }

    @Test
    void parseRejectsUnknownKeysAndBadValues() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> parse("colour", "red"));
        assertTrue(unknown.getMessage().contains("blockedArtists"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse("explicitPolicy", "maybe"));
        assertThrows(IllegalArgumentException.class, () -> parse("familiarBias", "lots"));
        assertThrows(IllegalArgumentException.class, () -> parse("familiarBias", ""));
        assertThrows(IllegalArgumentException.class, () -> parse(null, "x"));
    }

    @Test
    void settingsAreReadBackFromStoredPreferences() {
        PreferenceSettings s = PreferenceSettings.from(List.of(
                parse("blockedArtists", "b2,b1"),
                parse("explicitPolicy", "block"),
                parse("familiarBias", "0.25")));

        assertEquals(Set.of("b1", "b2"), s.blockedArtists());
        assertEquals(false, s.explicitAllowed().orElseThrow());
        assertEquals(0.25, s.familiarBias().orElseThrow(), 1e-9);
    }

    @Test
    void nothingStoredMeansNoOpinion() {
        PreferenceSettings s = PreferenceSettings.from(List.of());
        assertTrue(s.blockedArtists().isEmpty());
        assertTrue(s.explicitAllowed().isEmpty());
        assertTrue(s.familiarBias().isEmpty());
    }

    @Test
    void theDatabaseDownDefaultsMeanNoExplicitContentAndTheirBias() {
        PreferenceSettings s = PreferenceSettings.from(
                List.of(new UserPreference(UserPreference.DEFAULTS_KEY, "", false, 0.5)));
        assertEquals(false, s.explicitAllowed().orElseThrow());
        assertEquals(0.5, s.familiarBias().orElseThrow(), 1e-9);
    }

    @Test
    void applyToMergesBlocklistsAndOnlyEverTightensTheExplicitPolicy() {
        PreferenceSettings s = PreferenceSettings.from(List.of(
                parse("blockedArtists", "b1"),
                parse("explicitPolicy", "block")));

        PlaylistSpec merged = s.applyTo(spec(true, 0.6, List.of("b2")));

        assertEquals(Set.of("b1", "b2"), Set.copyOf(merged.blockedArtists()));
        assertFalse(merged.allowExplicit());
        assertEquals(0.6, merged.familiarRatio(), 1e-9);

        PreferenceSettings allowing = PreferenceSettings.from(List.of(parse("explicitPolicy", "allow")));
        assertFalse(allowing.applyTo(spec(false, 0.5, List.of())).allowExplicit(), "a spec that forbids explicit stays strict");
        assertTrue(allowing.applyTo(spec(true, 0.5, List.of())).allowExplicit());
    }

    @Test
    void applyToDoesNotChangeTheOriginalSpec() {
        PlaylistSpec original = spec(true, 0.5, List.of());
        PreferenceSettings.from(List.of(parse("blockedArtists", "b1"))).applyTo(original);
        assertTrue(original.blockedArtists().isEmpty());
    }

    @Test
    void displayShowsTheMeaningfulColumnForEachKey() {
        assertEquals("a,b", PreferenceSettings.display(parse("blockedArtists", "b,a")));
        assertEquals("block", PreferenceSettings.display(parse("explicitPolicy", "block")));
        assertEquals("0.7", PreferenceSettings.display(parse("familiarBias", "0.7")));
    }

    @Test
    void blockedTracksAreParsedStoredAndApplied() {
        String id = "0123456789abcdefghijAB";
        UserPreference pref = parse("blockedTracks", "spotify:track:" + id);

        PreferenceSettings settings = PreferenceSettings.from(List.of(pref));

        assertEquals(id, pref.value());
        assertEquals(java.util.Set.of(id), settings.blockedTracks());
        assertTrue(settings.applyTo(new PlaylistSpec(10, true, 0.5, List.of(), "x")).isTrackBlocked(id));
    }

    @Test
    void aBlockedTrackThatIsNotAnIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("blockedTracks", "Billie Jean"));
    }
}
