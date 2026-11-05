package com.musicmind.app.view;

import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import com.musicmind.domain.Verdict;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** F1: the taste profile with its genre breakdown, top artists and tracks, and a "Refresh profile" button. */
public final class ProfileView implements TabView {

    /** A profile and what the view needs to know about how it was got. */
    private record Shown(ListeningProfile profile, boolean stored, String memoryWarning, Map<String, Verdict> verdicts) { }

    private static final double GENRE_NAME_WIDTH = 160;
    private static final double GENRE_BAR_WIDTH = 260;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final ComboBox<String> range = new ComboBox<>();
    private final Button refresh;
    private final StatusLine status = new StatusLine();
    private final Label notice = UiParts.collapsing(UiParts.muted(""));
    private final Label generated = UiParts.collapsing(UiParts.muted(""));
    private final GridPane genres = new GridPane();
    private final VBox artists = new VBox();   // rows carry their own padding
    private final VBox tracks = new VBox();
    private final Label summary = UiParts.wrapped("");
    private final Node root;

    public ProfileView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        range.setItems(FXCollections.observableArrayList(ctx.controller().timeRanges()));
        range.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String raw) {
                return raw == null ? "" : format.rangeLabel(raw);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        range.getSelectionModel().select(ctx.config().profileDefaultRange());
        refresh = new Button(format.w("RefreshProfile"));
        refresh.getStyleClass().add("mm-primary");
        refresh.setDefaultButton(true);
        refresh.setOnAction(e -> rebuild());

        HBox controls = new HBox(UiParts.SPACE_S, new Label(format.w("Range")), range, refresh);
        controls.setAlignment(Pos.CENTER_LEFT);
        genres.setHgap(UiParts.SPACE_S);
        genres.setVgap(UiParts.SPACE_S);
        HBox lists = new HBox(UiParts.SPACE_L, card(format.w("TopArtists"), artists), card(format.w("TopTracks"), tracks));
        VBox summaryCard = card(format.w("Summary"), summary);

        VBox content = UiParts.page(format.w("Menu.profile"), format.w("Desc.profile"), "", UiParts.group(controls, status, generated, notice),
                card(format.w("TopGenres"), genres), lists, summaryCard);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        root = new LoginGate(ctx, scroll, false);
        status.info(format.w("ProfileNone"));
    }

    private static VBox card(String title, Node body) {
        VBox card = new VBox(UiParts.SPACE_S, UiParts.heading(title), body);
        card.getStyleClass().add("mm-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    @Override
    public String title() {
        return format.w("Menu.profile");
    }

    @Override
    public String description() {
        return format.w("Desc.profile");
    }

    @Override
    public Node node() {
        return root;
    }

    /** After login the stored profile is read from the database; Spotify is called only by "Refresh profile". */
    @Override
    public void onLogin(User user) {
        ctx.run(status, "ActionShowProfile", format.w("Working"), () -> {
            Optional<ListeningProfile> stored = ctx.controller().lastProfile(user);
            return stored.map(p -> new Shown(p, true, ctx.controller().memory().warning(),
                    ctx.controller().feedbackFor())).orElse(null);
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
        ctx.run(status, "ActionBuildProfile", format.w("ProfileBuilding"), () -> {
            ListeningProfile profile = ctx.controller().analyzeTaste(user, chosen);
            return new Shown(profile, false, ctx.controller().memory().warning(), ctx.controller().feedbackFor());
        }, this::show, refresh, range);
    }

    private void show(Shown shown) {
        ListeningProfile profile = shown.profile();
        status.info(shown.stored() ? format.w("ProfileStored") : "");
        generated.setText(format.generatedLine(profile.generatedAt(), profile.range()));
        if (shown.stored() && profile.range() != null && range.getItems().contains(profile.range())) {
            range.setValue(profile.range());
        }
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
        artists.getChildren().setAll(artistNames.isEmpty()
                ? List.of(placeholder(profile.topArtists().size(), "NamesNotLoaded"))
                : artistNames.stream().map(this::plainRow).toList());
        List<Track> named = profile.topTracks().stream().filter(t -> t.title() != null && !t.title().isBlank())
                .limit(ctx.config().guiTracksShown()).toList();
        tracks.getChildren().setAll(named.isEmpty()
                ? List.of(placeholder(profile.topTracks().size(), "TrackNamesNotLoaded"))
                : named.stream().map(t -> TrackRow.of(ctx, status, t, TrackRow.Options.feedbackOnly(),
                        shown.verdicts().get(t.id()))).toList());
        summary.setText(profile.summary() == null ? "" : profile.summary());
    }

    private Node plainRow(String name) {
        Label label = new Label(name);
        label.getStyleClass().add("mm-track-title");
        HBox row = new HBox(label);
        row.getStyleClass().add("mm-row");
        return row;
    }

    /** What to show when only counts are stored: a plain line, or "nothing yet" when there are none at all. */
    private Node placeholder(int count, String key) {
        return UiParts.muted(count > 0 ? format.w(key) : format.w("NoneYet"));
    }
}
