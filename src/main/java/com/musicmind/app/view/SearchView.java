package com.musicmind.app.view;

import com.musicmind.domain.Artist;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import com.musicmind.domain.Verdict;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** F3: a search bar with a list of found songs (each with "+ Add") and the visible list of added songs. */
public final class SearchView implements TabView {

    /** Everything the last search left in the controller, read on the worker before it changes. */
    private record Found(List<Track> tracks, List<Artist> artists, String label, String notice,
                         Map<String, Verdict> verdicts, List<Track> waiting) { }

    /** The candidate list, the verdicts already given and whether the database accepted the change. */
    private record Candidates(List<Track> tracks, Map<String, Verdict> verdicts, String warning) { }

    private static final double LIST_HEIGHT = 220;
    private static final double MIN_LIST_HEIGHT = 48;
    private static final double SCROLL_PADDING = 4;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final TextField query = new TextField();
    private final Button search;
    private final StatusLine status = new StatusLine();
    private final VBox results = new VBox(2);
    private final VBox candidates = new VBox(2);
    private final Label artists = UiParts.wrapped("");
    private final Label notices = UiParts.muted("");
    private final Label waitingCount = UiParts.muted("");
    private final Button clear;
    private final Node root;
    /** The songs the last search found, whole; the results show these minus the ones waiting. */
    private List<Track> foundTracks;
    private Map<String, Verdict> foundVerdicts = Map.of();
    /** The songs waiting for the next playlist, as last shown. */
    private List<Track> waitingTracks = List.of();

    public SearchView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        query.setPromptText(format.w("SearchPrompt"));
        HBox.setHgrow(query, Priority.ALWAYS);
        search = new Button(format.w("SearchButton"));
        search.getStyleClass().add("mm-primary");
        search.setDefaultButton(true);
        search.setOnAction(e -> runSearch());
        query.setOnAction(e -> runSearch());
        clear = new Button(format.w("ClearCandidates"));
        clear.setOnAction(e -> change(() -> ctx.controller().clearCandidates(), state -> format.w("CandidatesCleared")));

        results.getChildren().add(UiParts.muted(format.w("NoResults")));
        candidates.getChildren().add(UiParts.muted(format.w("NoCandidates")));

        HBox bar = new HBox(UiParts.GAP, query, search);
        bar.setAlignment(Pos.CENTER_LEFT);
        HBox resultsHeading = new HBox(UiParts.GAP, UiParts.heading(format.w("Results")), waitingCount);
        resultsHeading.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(4, resultsHeading, scroll(results, false));
        VBox right = new VBox(4, UiParts.heading(format.w("Candidates")), scroll(candidates, true), clear);
        ctx.onDataChanged(() -> {
            if (ctx.userProperty().get() != null) {
                onLogin(ctx.userProperty().get());
            }
        });
        VBox page = UiParts.page(format.w("Menu.search"), format.w("Desc.search"), format.w("Example.search"), bar, status, new VBox(UiParts.PAD, left, right), artists, notices);
        root = new LoginGate(ctx, page, false);
    }

    /** @param shrink true to fit the content (up to the list height) so no empty gap sits before the button below */
    private static ScrollPane scroll(VBox content, boolean shrink) {
        ScrollPane pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        if (shrink) {
            pane.prefHeightProperty().bind(Bindings.min(LIST_HEIGHT,
                    Bindings.max(MIN_LIST_HEIGHT, content.heightProperty().add(SCROLL_PADDING))));
        } else {
            pane.setPrefHeight(LIST_HEIGHT);
        }
        return pane;
    }

    @Override
    public String title() {
        return format.w("Menu.search");
    }

    @Override
    public String description() {
        return format.w("Desc.search");
    }

    @Override
    public String example() {
        return format.w("Example.search");
    }

    @Override
    public Node node() {
        return root;
    }

    /** After login the saved candidates are shown. */
    @Override
    public void onLogin(User user) {
        ctx.run(status, "ActionShowCandidates", format.w("Working"), this::candidateState, this::show, search);
    }

    private void runSearch() {
        String text = query.getText() == null ? "" : query.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        ctx.run(status, "ActionSearch", format.w("Searching"), () -> {
            List<Track> tracks = ctx.controller().searchMusic(text);
            return new Found(tracks, ctx.controller().lastSearchArtists(), ctx.controller().lastSearchLabel(),
                    ctx.controller().lastSearchNotice(), ctx.controller().feedbackFor(), ctx.controller().getCandidates());
        }, found -> {
            foundTracks = found.tracks();
            foundVerdicts = found.verdicts();
            waitingTracks = found.waiting();
            showResults();
            artists.setText(found.artists().isEmpty() ? ""
                    : ctx.config().wording("cliArtistsFound") + " "
                    + String.join(", ", format.artistNames(found.artists(), ctx.config().guiArtistsShown())));
            notices.setText(joinLines(found.label(), found.notice()));
        }, search);
    }

    private void add(Track track) {
        boolean[] already = {false};
        change(() -> {
            already[0] = ctx.controller().getCandidates().stream().anyMatch(t -> t.id().equals(track.id()));
            if (!already[0]) {
                ctx.controller().addCandidate(track.id());
            }
        }, state -> already[0] ? format.candidateAlreadyWaiting(track.title())
                : format.candidateAdded(track.title(), state.tracks().size()));
    }

    private void remove(Track track) {
        change(() -> ctx.controller().removeCandidate(track.id()), state -> format.w("CandidateRemoved", track.title()));
    }

    /** Applies a change to the candidate list on the worker, then shows the list as it is now. */
    private void change(Runnable change, Function<Candidates, String> doneMessage) {
        ctx.run(status, "ActionChangeCandidates", format.w("Working"), () -> {
            change.run();
            return candidateState();
        }, state -> {
            show(state);
            if (state.warning() == null) {
                status.info(doneMessage.apply(state));
            }
        }, search, clear);
    }

    private Candidates candidateState() {
        String warning = ctx.controller().memory().warning();
        List<Track> tracks = ctx.controller().getCandidates();
        Map<String, Verdict> verdicts = ctx.controller().feedbackFor();
        return new Candidates(tracks, verdicts, warning != null ? warning : ctx.controller().memory().warning());
    }

    /** The results are the found songs that are not waiting: adding one removes it here, removing it brings it back. */
    private void showResults() {
        if (foundTracks == null) {
            return;
        }
        List<Track> visible = format.withoutWaiting(foundTracks, waitingTracks);
        fill(results, visible, foundVerdicts, TrackRow.Options.feedbackOnly().withPlay().withAdd(this::add),
                foundTracks.isEmpty() || visible.isEmpty() && waitingTracks.isEmpty() ? "NoMatches" : "AllFoundWaiting");
    }

    private void show(Candidates state) {
        waitingTracks = state.tracks();
        showResults();
        fill(candidates, state.tracks(), state.verdicts(), TrackRow.Options.feedbackOnly().withPlay().withRemove(this::remove), "NoCandidates");
        waitingCount.setText(format.waitingCount(state.tracks().size()));
        if (state.warning() != null) {
            status.error(ctx.config().wording("cliNotSaved").formatted(state.warning()));
        }
    }

    private void fill(VBox box, List<Track> tracks, Map<String, Verdict> verdicts, TrackRow.Options options, String emptyKey) {
        if (tracks.isEmpty()) {
            box.getChildren().setAll(UiParts.muted(format.w(emptyKey)));
            return;
        }
        box.getChildren().setAll(tracks.stream()
                .map(t -> TrackRow.of(ctx, status, t, options, verdicts.get(t.id()))).toList());
    }

    private static String joinLines(String... lines) {
        return String.join("\n", java.util.Arrays.stream(lines).filter(l -> l != null && !l.isBlank()).toList());
    }
}
