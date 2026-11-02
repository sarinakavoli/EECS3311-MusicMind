package com.musicmind.app.view;

import com.musicmind.domain.Artist;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** F3: a search bar with a results list (each with "add to candidates") and the visible candidate list. */
public final class SearchView implements TabView {

    /** Everything the last search left in the controller, read on the worker before it changes. */
    private record Found(List<Track> tracks, List<Artist> artists, String label, String notice) { }

    /** The candidate list and whether the database accepted the change. */
    private record Candidates(List<Track> tracks, String warning) { }

    private static final double LIST_HEIGHT = 320;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final TextField query = new TextField();
    private final Button search;
    private final StatusLine status = new StatusLine();
    private final ListView<Track> results = new ListView<>();
    private final ListView<Track> candidates = new ListView<>();
    private final Label artists = UiParts.wrapped("");
    private final Label notices = UiParts.muted("");
    private final Button clear;
    private final Node root;

    public SearchView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        query.setPromptText(format.w("SearchPrompt"));
        HBox.setHgrow(query, Priority.ALWAYS);
        search = new Button(format.w("SearchButton"));
        search.setDefaultButton(true);
        search.setOnAction(e -> runSearch());
        query.setOnAction(e -> runSearch());
        clear = new Button(format.w("ClearCandidates"));
        clear.setOnAction(e -> change(() -> {
            ctx.controller().clearCandidates();
        }, format.w("CandidatesCleared")));

        results.setPlaceholder(new Label(format.w("NoResults")));
        results.setCellFactory(list -> new TrackCell(format.w("AddCandidate"), this::add));
        results.setPrefHeight(LIST_HEIGHT);
        candidates.setPlaceholder(new Label(format.w("NoCandidates")));
        candidates.setCellFactory(list -> new TrackCell(format.w("RemoveCandidate"), this::remove));
        candidates.setPrefHeight(LIST_HEIGHT);

        HBox bar = new HBox(UiParts.GAP, query, search);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(4, UiParts.heading(format.w("Results")), results);
        VBox right = new VBox(4, UiParts.heading(format.w("Candidates")), candidates, clear);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        ctx.onDataChanged(() -> {
            if (ctx.userProperty().get() != null) {
                onLogin(ctx.userProperty().get());
            }
        });
        VBox page = UiParts.page(bar, status, new HBox(UiParts.PAD, left, right), artists, notices);
        root = new LoginGate(ctx, page, false);
    }

    @Override
    public String title() {
        return format.w("TabSearch");
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
                    ctx.controller().lastSearchNotice());
        }, found -> {
            results.setItems(FXCollections.observableArrayList(found.tracks()));
            artists.setText(found.artists().isEmpty() ? ""
                    : ctx.config().wording("cliArtistsFound") + " "
                    + String.join(", ", format.artistNames(found.artists(), ctx.config().guiArtistsShown())));
            notices.setText(joinLines(found.label(), found.notice()));
        }, search);
    }

    private void add(Track track) {
        change(() -> ctx.controller().addCandidate(track.id()), format.w("CandidateAdded", track.title()));
    }

    private void remove(Track track) {
        change(() -> ctx.controller().removeCandidate(track.id()), format.w("CandidateRemoved", track.title()));
    }

    /** Applies a change to the candidate list on the worker, then shows the list as it is now. */
    private void change(Runnable change, String doneMessage) {
        ctx.run(status, "ActionChangeCandidates", format.w("Working"), () -> {
            change.run();
            return candidateState();
        }, state -> {
            show(state);
            if (state.warning() == null) {
                status.info(doneMessage);
            }
        }, search, clear);
    }

    private Candidates candidateState() {
        String warning = ctx.controller().memory().warning();
        List<Track> tracks = ctx.controller().getCandidates();
        return new Candidates(tracks, warning != null ? warning : ctx.controller().memory().warning());
    }

    private void show(Candidates state) {
        candidates.setItems(FXCollections.observableArrayList(state.tracks()));
        if (state.warning() != null) {
            status.error(ctx.config().wording("cliNotSaved").formatted(state.warning()));
        }
    }

    private static String joinLines(String... lines) {
        return String.join("\n", java.util.Arrays.stream(lines).filter(l -> l != null && !l.isBlank()).toList());
    }

    /** A row: the title, its length and one button. */
    private final class TrackCell extends ListCell<Track> {
        private final Label title = new Label();
        private final Label length = UiParts.muted("");
        private final Button action;
        private final HBox row;

        TrackCell(String actionLabel, Consumer<Track> onAction) {
            action = new Button(actionLabel);
            action.setOnAction(e -> {
                if (getItem() != null) {
                    onAction.accept(getItem());
                }
            });
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row = new HBox(UiParts.GAP, title, spacer, length, action);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Track track, boolean empty) {
            super.updateItem(track, empty);
            if (empty || track == null) {
                setGraphic(null);
                return;
            }
            title.setText(track.title());
            length.setText(format.duration(track.durationMs()));
            setGraphic(row);
        }
    }
}
