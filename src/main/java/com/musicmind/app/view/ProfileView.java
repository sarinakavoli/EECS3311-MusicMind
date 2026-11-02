package com.musicmind.app.view;

import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/** F1: the taste profile with its genre breakdown, top artists and tracks, and a "Refresh profile" button. */
public final class ProfileView implements TabView {

    /** A profile and what the view needs to know about how it was got. */
    private record Shown(ListeningProfile profile, boolean stored, String memoryWarning) { }

    private static final double GENRE_NAME_WIDTH = 160;
    private static final double GENRE_BAR_WIDTH = 260;
    private static final double LIST_HEIGHT = 200;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final ComboBox<String> range = new ComboBox<>();
    private final Button refresh;
    private final StatusLine status = new StatusLine();
    private final Label notice = UiParts.muted("");
    private final Label generated = UiParts.muted("");
    private final GridPane genres = new GridPane();
    private final ListView<String> artists = new ListView<>();
    private final ListView<String> tracks = new ListView<>();
    private final Label summary = UiParts.wrapped("");
    private final Node root;

    public ProfileView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        range.setItems(FXCollections.observableArrayList(ctx.config().profileRanges()));
        range.getSelectionModel().select(ctx.config().profileDefaultRange());
        refresh = new Button(format.w("RefreshProfile"));
        refresh.setDefaultButton(true);
        refresh.setOnAction(e -> rebuild());

        HBox controls = new HBox(UiParts.GAP, new Label(format.w("Range")), range, refresh);
        controls.setAlignment(Pos.CENTER_LEFT);
        genres.setHgap(UiParts.GAP);
        genres.setVgap(4);
        artists.setPrefHeight(LIST_HEIGHT);
        tracks.setPrefHeight(LIST_HEIGHT);
        HBox lists = new HBox(UiParts.PAD, listColumn(format.w("TopArtists"), artists),
                listColumn(format.w("TopTracks"), tracks));

        VBox content = UiParts.page(controls, status, generated, notice, UiParts.heading(format.w("TopGenres")), genres, lists,
                UiParts.heading(format.w("Summary")), summary,
                UiParts.muted(ctx.config().spotifyAttribution()), UiParts.muted(ctx.config().genreAttribution()));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        root = new LoginGate(ctx, scroll, false);
        status.info(format.w("ProfileNone"));
    }

    private VBox listColumn(String title, ListView<String> list) {
        VBox column = new VBox(4, UiParts.heading(title), list);
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    @Override
    public String title() {
        return format.w("TabProfile");
    }

    @Override
    public Node node() {
        return root;
    }

    /** After login the stored profile is read from the database; Spotify is called only by "Refresh profile". */
    @Override
    public void onLogin(User user) {
        ctx.run(status, "show your profile", format.w("Working"), () -> {
            Optional<ListeningProfile> stored = ctx.controller().lastProfile(user);
            return stored.map(p -> new Shown(p, true, ctx.controller().memory().warning())).orElse(null);
        }, shown -> {
            if (shown != null) {
                show(shown);
            } else {
                status.info(format.w("ProfileNone"));
            }
        }, refresh, range);
    }

    private void rebuild() {
        User user = ctx.userProperty().get();
        String chosen = range.getValue();
        ctx.run(status, "build your profile", format.w("ProfileBuilding"), () -> {
            ListeningProfile profile = ctx.controller().analyzeTaste(user, chosen);
            return new Shown(profile, false, ctx.controller().memory().warning());
        }, this::show, refresh, range);
    }

    private void show(Shown shown) {
        ListeningProfile profile = shown.profile();
        status.info(shown.stored() ? format.w("ProfileStored") : "");
        generated.setText(profile.generatedAt() == null ? ""
                : format.w("ProfileGenerated", profile.generatedAt(), profile.range() == null ? "" : profile.range()));
        List<String> notices = new java.util.ArrayList<>(profile.notices());
        if (shown.memoryWarning() != null) {
            notices.add(ctx.config().wording("cliNotSaved").formatted(shown.memoryWarning()));
        }
        notice.setText(String.join("\n", notices));

        genres.getChildren().clear();
        List<ViewFormat.GenreRow> rows = format.genreRows(profile, ctx.config().guiGenresShown());
        if (rows.isEmpty()) {
            genres.add(new Label(ctx.config().wording("cliNoGenreData")), 0, 0);
        }
        for (int i = 0; i < rows.size(); i++) {
            ViewFormat.GenreRow row = rows.get(i);
            Label name = new Label(row.name());
            name.setMinWidth(GENRE_NAME_WIDTH);
            ProgressBar bar = new ProgressBar(row.bar());
            bar.setPrefWidth(GENRE_BAR_WIDTH);
            genres.addRow(i, name, bar, new Label(row.percent()));
        }

        List<String> artistNames = format.artistNames(profile.topArtists(), ctx.config().guiArtistsShown());
        artists.setItems(FXCollections.observableArrayList(artistNames.isEmpty()
                ? List.of(ctx.config().wording("cliTopArtistsCounted").formatted(profile.topArtists().size()))
                : artistNames));
        List<String> trackTitles = profile.topTracks().stream().map(Track::title).filter(t -> t != null && !t.isBlank())
                .limit(ctx.config().guiTracksShown()).toList();
        tracks.setItems(FXCollections.observableArrayList(trackTitles.isEmpty()
                ? List.of(ctx.config().wording("cliTracksCounted").formatted(profile.topTracks().size(), profile.savedTrackIds().size()))
                : trackTitles));
        summary.setText(profile.summary() == null ? "" : profile.summary());
    }
}
