package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstraintCheckerTest {

    private final Config config = Config.of(Map.of());
    private final ConstraintChecker checker = new ConstraintChecker(config);

    /** {@code n} tracks of four minutes each. */
    private static List<Track> tracks(String prefix, int n, boolean explicit, String artist) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Track(prefix + i, "spotify:track:" + prefix + i, "T", 240_000, explicit, List.of(artist)))
                .toList();
    }

    private static PlaylistSpec spec(int minutes, boolean explicit, double ratio) {
        return new PlaylistSpec(minutes, explicit, ratio, List.of(), "n");
    }

    private static Relaxation of(FeasibilityReport r, Relaxation.Kind kind) {
        return r.relaxations().stream().filter(x -> x.kind() == kind).findFirst().orElse(null);
    }

    @Test
    void aPoolThatCoversTheTargetAndTheRatioIsFeasible() {
        CandidatePool pool = new CandidatePool(tracks("f", 12, false, "a"), tracks("n", 12, false, "b"));
        FeasibilityReport r = checker.checkFeasibility(spec(60, false, 0.7), pool);
        assertTrue(r.feasible());
        assertTrue(r.conflicts().isEmpty() && r.relaxations().isEmpty());
    }

    @Test
    void notEnoughEligibleMinutesOffersAShorterPlaylistThatKeepsTheRatio() {
        CandidatePool pool = new CandidatePool(tracks("f", 5, false, "a"), tracks("n", 5, false, "b"));
        FeasibilityReport r = checker.checkFeasibility(spec(90, false, 0.7), pool);
        assertFalse(r.feasible());
        assertFalse(r.conflicts().isEmpty());
        Relaxation shorten = of(r, Relaxation.Kind.SHORTEN_DURATION);
        // 20 familiar minutes at a 70% share fill at most 28 minutes.
        assertEquals(28, shorten.value());
        assertEquals(28, shorten.applyTo(spec(90, false, 0.7)).targetMinutes());
        assertEquals(r.relaxations().stream().map(Relaxation::description).toList(), r.relaxationOptions());
    }

    @Test
    void aRatioOneSideCannotFillIsReportedAndOffersAnotherRatio() {
        // 60 minutes at 100% familiar, but only 20 familiar minutes; plenty of new tracks.
        CandidatePool pool = new CandidatePool(tracks("f", 5, false, "a"), tracks("n", 20, false, "b"));
        FeasibilityReport r = checker.checkFeasibility(spec(60, false, 1.0), pool);
        assertFalse(r.feasible());
        Relaxation ratio = of(r, Relaxation.Kind.CHANGE_FAMILIAR_RATIO);
        assertEquals(0.33, ratio.value(), 0.001);
        assertEquals(0.33, ratio.applyTo(spec(60, false, 1.0)).familiarRatio(), 0.001);
    }

    @Test
    void explicitTracksThatWouldHelpAreOfferedAsARelaxation() {
        CandidatePool pool = new CandidatePool(tracks("f", 5, true, "a"), tracks("n", 5, false, "b"));
        FeasibilityReport r = checker.checkFeasibility(spec(60, false, 0.5), pool);
        assertFalse(r.feasible());
        Relaxation allow = of(r, Relaxation.Kind.ALLOW_EXPLICIT);
        assertTrue(allow.applyTo(spec(60, false, 0.5)).allowExplicit());
    }

    @Test
    void blockedArtistsAreNotEligible() {
        CandidatePool pool = new CandidatePool(tracks("f", 12, false, "bad"), tracks("n", 12, false, "b"));
        PlaylistSpec s = new PlaylistSpec(60, false, 0.5, List.of("bad"), "n");
        FeasibilityReport r = checker.checkFeasibility(s, pool);
        assertFalse(r.feasible());
    }

    @Test
    void nothingEligibleAtAllHasNoRelaxationToOffer() {
        FeasibilityReport r = checker.checkFeasibility(spec(60, false, 0.5),
                new CandidatePool(List.of(), List.of()));
        assertFalse(r.feasible());
        assertTrue(r.relaxations().isEmpty());
        assertEquals(config.wording("conflictNoTracks"), r.conflicts().get(0));
    }

    @Test
    void aShortfallAfterSelectionOffersTheMinutesThatWereReached() {
        CandidatePool pool = new CandidatePool(tracks("f", 12, false, "a"), tracks("n", 12, false, "b"));
        FeasibilityReport r = checker.describeShortfall(spec(60, false, 0.5), pool, 40);
        assertFalse(r.feasible());
        assertEquals(40, of(r, Relaxation.Kind.SHORTEN_DURATION).value());
    }

    // ---- edits (F11 during modify) ----

    private List<com.musicmind.agent.command.PlaylistCommand> removals(List<Track> from, int n) {
        com.musicmind.agent.command.FakeMusicService music = new com.musicmind.agent.command.FakeMusicService();
        return from.stream().limit(n).<com.musicmind.agent.command.PlaylistCommand>map(
                t -> new com.musicmind.agent.command.RemoveTrackCommand(config, music, "p", t)).toList();
    }

    @Test
    void anEditThatLeavesTheMinimumIsFeasible() {
        List<Track> before = tracks("t", 3, false, "a");
        assertTrue(checker.checkEdit(before, removals(before, 2)).feasible());
    }

    @Test
    void anEditThatEmptiesThePlaylistOffersTheLargestPartialEdit() {
        List<Track> before = tracks("t", 3, false, "a");

        FeasibilityReport r = checker.checkEdit(before, removals(before, 3));

        assertFalse(r.feasible());
        Relaxation partial = of(r, Relaxation.Kind.APPLY_PARTIAL);
        assertEquals(2, partial.value());
        assertTrue(partial.description().contains("first 2 of 3"), partial.description());
    }

    @Test
    void anEditWhoseFirstStepAlreadyEmptiesThePlaylistHasNoRelaxation() {
        List<Track> before = tracks("t", 1, false, "a");

        FeasibilityReport r = checker.checkEdit(before, removals(before, 1));

        assertFalse(r.feasible());
        assertTrue(r.relaxations().isEmpty());
    }

    @Test
    void blockedTracksDoNotCountTowardTheMinutesAvailable() {
        List<Track> few = tracks("f", 12, false, "a");
        CandidatePool pool = new CandidatePool(few, List.of());
        PlaylistSpec spec = spec(60, false, 1.0).withBlockedTracks(few.subList(0, 6).stream().map(Track::id).toList());

        assertFalse(checker.checkFeasibility(spec, pool).feasible());
    }
}
