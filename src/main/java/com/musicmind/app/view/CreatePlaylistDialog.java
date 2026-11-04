package com.musicmind.app.view;

import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.domain.Verdict;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * F5: collects the goal and the constraints (length in hours and minutes, explicit lyrics, the mix of artists
 * you know and new ones, the songs waiting from Find Songs, name), shows the state names of the run as its
 * progress, and then shows the finished playlist and why its songs were chosen. An infeasible request opens
 * the F11 dialog from the controller's negotiation handler.
 */
public final class CreatePlaylistDialog {

    /** A finished run: the playlist, the run's notices, its explanation and the verdicts already given. */
    private record Created(Playlist playlist, List<String> notices, String explanation, Map<String, Verdict> verdicts) { }

    private static final double TRACKS_HEIGHT = 200;
    private static final double WAITING_HEIGHT = 90;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final Consumer<Playlist> onCreated;
    private final Dialog<Void> dialog = new Dialog<>();
    private final TextArea goal = new TextArea();
    private final TextField hours = new TextField();
    private final TextField minutes = new TextField();
    private final CheckBox allowExplicit = new CheckBox();
    private final CheckBox mixFromGoal = new CheckBox();
    /** The share of new artists, as the Discover slider shows it; the familiar share sent is 1 minus this. */
    private final Slider novelty = new Slider(0, 1, 0);
    private final Label mixLabel = new Label();
    private final VBox mixBox = new VBox(UiParts.GAP);
    private final CheckBox useWaiting = new CheckBox();
    private final VBox waitingBox = new VBox(UiParts.GAP);
    private final TextField name = new TextField();
    private final StatusLine status = new StatusLine();
    private final VBox result = new VBox(UiParts.GAP);
    private final ButtonType createButton;
    private boolean haveWaiting;
    private Node createNode;

    /** @param onCreated called on the JavaFX thread with the playlist once it exists */
    public CreatePlaylistDialog(GuiContext ctx, Consumer<Playlist> onCreated) {
        this.ctx = ctx;
        this.format = ctx.format();
        this.onCreated = onCreated;
        createButton = new ButtonType(format.w("Create"), ButtonBar.ButtonData.OK_DONE);

        dialog.setTitle(format.w("CreateTitle"));
        dialog.setHeaderText(format.w("CreateTitle"));
        goal.setPromptText(format.w("GoalHint"));
        goal.setPrefRowCount(3);
        goal.setWrapText(true);

        hours.setPromptText("0");
        minutes.setPromptText("0");
        hours.getStyleClass().add("mm-length-field");
        minutes.getStyleClass().add("mm-length-field");
        HBox length = new HBox(UiParts.GAP, hours, UiParts.muted(format.w("Hours")), minutes, UiParts.muted(format.w("Minutes")));
        length.setAlignment(Pos.CENTER_LEFT);

        allowExplicit.setText(format.w("AllowExplicit"));
        allowExplicit.setSelected(true);

        mixFromGoal.setText(format.w("FamiliarFromGoal"));
        mixFromGoal.setSelected(true);
        novelty.setValue(1 - ctx.config().parserDefaultFamiliarRatio());
        mixLabel.getStyleClass().add("mm-mix-label");
        mixLabel.textProperty().bind(Bindings.createStringBinding(
                () -> format.mixLabel(1 - novelty.getValue()), novelty.valueProperty()));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        VBox sliderBox = new VBox(novelty, new HBox(UiParts.muted(format.w("MixKnown")), gap, UiParts.muted(format.w("MixNew"))));
        sliderBox.getStyleClass().add("mm-slider-box");
        mixBox.getChildren().addAll(mixLabel, sliderBox);
        // Nothing to choose while the AI picks the mix, so the slider is hidden rather than shown locked.
        mixBox.visibleProperty().bind(mixFromGoal.selectedProperty().not());
        mixBox.managedProperty().bind(mixBox.visibleProperty());
        Label mixHint = UiParts.muted(format.w("MixHint"));
        mixHint.visibleProperty().bind(mixFromGoal.selectedProperty());
        mixHint.managedProperty().bind(mixHint.visibleProperty());

        useWaiting.setSelected(true);
        waitingBox.setVisible(false);
        waitingBox.setManaged(false);
        name.setPromptText(format.w("PlaylistName"));

        VBox content = new VBox(UiParts.GAP, UiParts.muted(format.w("Goal")), goal,
                UiParts.muted(format.w("Length")), length, UiParts.muted(format.w("LengthHint")), allowExplicit,
                mixFromGoal, mixHint, mixBox, waitingBox, UiParts.muted(format.w("PlaylistName")), name, status, result);
        content.getStyleClass().add("mm-dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, new ButtonType(format.w("Close"),
                ButtonBar.ButtonData.CANCEL_CLOSE));
        UiParts.dark(dialog.getDialogPane());
        Node create = dialog.getDialogPane().lookupButton(createButton);
        create.getStyleClass().add("mm-primary");
        createNode = create;
        create.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();   // the dialog stays open to show progress and the result
            start(create);
        });
    }

    /** Shows the dialog without blocking; the creation runs on the worker. */
    public void show() {
        show((Window) null);
    }

    /** The same, as a child of {@code owner} so it opens over the window and shares its look. */
    public void show(Window owner) {
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.show();
        loadWaiting();
    }

    /** Reads the songs waiting from Find Songs; the choice to start with them shows only when there are some. */
    private void loadWaiting() {
        ctx.run(status, "ActionShowCandidates", format.w("Working"), () -> ctx.controller().getCandidates(), waiting -> {
            haveWaiting = !waiting.isEmpty();
            waitingBox.setVisible(haveWaiting);
            waitingBox.setManaged(haveWaiting);
            if (!haveWaiting) {
                return;
            }
            useWaiting.setText(format.w(waiting.size() == 1 ? "UseWaitingOne" : "UseWaitingMany", waiting.size()));
            VBox songs = new VBox(2);
            waiting.forEach(t -> songs.getChildren().add(UiParts.muted(format.trackTitleLine(t))));
            ScrollPane scroll = new ScrollPane(songs);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(WAITING_HEIGHT);
            scroll.setMaxHeight(WAITING_HEIGHT);
            scroll.getStyleClass().add("mm-why-scroll");
            waitingBox.getChildren().setAll(useWaiting, UiParts.muted(format.w("UseWaitingHint")), scroll);
            dialog.getDialogPane().getScene().getWindow().sizeToScene();
        }, createNode);   // Create stays off until the waiting songs are known, so they are not missed
    }

    private void start(Node create) {
        String text = goal.getText() == null ? "" : goal.getText().strip();
        if (text.isEmpty()) {
            status.error(format.w("NeedGoal"));
            return;
        }
        int targetMinutes;
        try {
            targetMinutes = format.parseHoursMinutes(hours.getText(), minutes.getText());
        } catch (IllegalArgumentException e) {
            status.error(e.getMessage());
            return;
        }
        double ratio = mixFromGoal.isSelected() ? Double.NaN : 1 - novelty.getValue();
        String playlistName = name.getText() == null || name.getText().isBlank() ? null : name.getText().strip();
        PlaylistSpec flags = new PlaylistSpec(targetMinutes, allowExplicit.isSelected(), ratio, List.of(), playlistName);
        boolean startWithWaiting = haveWaiting && useWaiting.isSelected();
        result.getChildren().clear();
        ctx.run(status, "ActionCreate", format.w("Creating"), () -> {
            ctx.controller().setProgressListener(state -> Platform.runLater(() -> status.busy(format.progress(state))));
            try {
                Playlist playlist = ctx.controller().createPlaylist(
                        new AgentRequest(text, RequestKind.CREATE_PLAYLIST, flags, startWithWaiting));
                AgentSession session = ctx.controller().lastSession();
                return new Created(playlist, session == null ? List.of() : session.notices(),
                        session == null ? null : session.explanation(), ctx.controller().feedbackFor());
            } finally {
                ctx.controller().setProgressListener(null);   // a null listener means no progress is reported
            }
        }, this::showResult, create, goal, hours, minutes, allowExplicit, mixFromGoal, novelty, useWaiting, name);
    }

    private void showResult(Created created) {
        Playlist playlist = created.playlist();
        status.info(format.w("Created", playlist.name(), playlist.tracks().size()));
        VBox rows = new VBox(2);
        for (Track track : playlist.tracks()) {
            rows.getChildren().add(TrackRow.of(ctx, status, track, TrackRow.Options.feedbackOnly(),
                    created.verdicts().get(track.id())));
        }
        ScrollPane tracks = new ScrollPane(rows);
        tracks.setFitToWidth(true);
        tracks.setPrefViewportHeight(TRACKS_HEIGHT);
        tracks.setMaxHeight(TRACKS_HEIGHT);
        result.getChildren().setAll(tracks);
        created.notices().forEach(n -> result.getChildren().add(UiParts.muted(n)));
        result.getChildren().add(WhyExpander.of(format, "WhyTheseSongs", created.explanation()));
        dialog.getDialogPane().getScene().getWindow().sizeToScene();
        onCreated.accept(playlist);
    }
}
