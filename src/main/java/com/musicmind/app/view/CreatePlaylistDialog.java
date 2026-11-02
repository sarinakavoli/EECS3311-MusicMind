package com.musicmind.app.view;

import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.util.StringConverter;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * F5: collects the goal and the constraints (length, explicit tracks, familiar-vs-new share, name), shows the
 * state names of the run as its progress, and then shows the finished playlist and why its tracks were chosen.
 * An infeasible request opens the F11 dialog from the controller's negotiation handler.
 */
public final class CreatePlaylistDialog {

    /** A finished run: the playlist, the run's notices and its explanation, read on the worker. */
    private record Created(Playlist playlist, List<String> notices, String explanation) { }

    private static final double TRACKS_HEIGHT = 200;
    private static final double WIDTH = 520;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final Consumer<Playlist> onCreated;
    private final Dialog<Void> dialog = new Dialog<>();
    private final TextArea goal = new TextArea();
    private final Spinner<Integer> minutes;
    private final CheckBox allowExplicit = new CheckBox();
    private final CheckBox familiarFromGoal = new CheckBox();
    private final Slider familiar = new Slider(0, 1, 0.5);
    private final Label familiarLabel = new Label();
    private final TextField name = new TextField();
    private final StatusLine status = new StatusLine();
    private final VBox result = new VBox(UiParts.GAP);
    private final ButtonType createButton;

    /** @param onCreated called on the JavaFX thread with the playlist once it exists */
    public CreatePlaylistDialog(GuiContext ctx, Consumer<Playlist> onCreated) {
        this.ctx = ctx;
        this.format = ctx.format();
        this.onCreated = onCreated;
        this.minutes = new Spinner<>(0, ctx.config().parserMaxTargetMinutes(), 0);
        minutes.setEditable(true);
        // Digits only, and a converter that never throws: the Spinner commits the editor itself when focus leaves it.
        minutes.getEditor().setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().chars().allMatch(Character::isDigit) ? change : null));
        minutes.getValueFactory().setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "0" : value.toString();
            }

            @Override
            public Integer fromString(String text) {
                return format.parseMinutes(text).orElse(0);
            }
        });
        createButton = new ButtonType(format.w("Create"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);

        dialog.setTitle(format.w("CreateTitle"));
        dialog.setHeaderText(format.w("CreateTitle"));
        goal.setPromptText(format.w("GoalHint"));
        goal.setPrefRowCount(3);
        goal.setWrapText(true);
        allowExplicit.setText(format.w("AllowExplicit"));
        allowExplicit.setSelected(true);
        familiarFromGoal.setText(format.w("FamiliarFromGoal"));
        familiarFromGoal.setSelected(true);
        familiar.disableProperty().bind(familiarFromGoal.selectedProperty());
        familiarLabel.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> format.w("FamiliarMix", Math.round(familiar.getValue() * 100), 100 - Math.round(familiar.getValue() * 100)),
                familiar.valueProperty()));
        name.setPromptText(format.w("PlaylistName"));

        VBox content = new VBox(UiParts.GAP, new Label(format.w("Goal")), goal,
                new Label(format.w("TargetMinutes")), minutes, allowExplicit,
                familiarFromGoal, familiar, familiarLabel, new Label(format.w("PlaylistName")), name, status, result);
        content.setPrefWidth(WIDTH);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, new ButtonType(format.w("Close"),
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        Node create = dialog.getDialogPane().lookupButton(createButton);
        create.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();   // the dialog stays open to show progress and the result
            start(create);
        });
    }

    /** Shows the dialog without blocking; the creation runs on the worker. */
    public void show() {
        dialog.show();
    }

    private void start(Node create) {
        String text = goal.getText() == null ? "" : goal.getText().strip();
        if (text.isEmpty()) {
            status.error(format.w("NeedGoal"));
            return;
        }
        java.util.OptionalInt parsed = format.parseMinutes(minutes.getEditor().getText());
        if (parsed.isEmpty()) {
            status.error(format.w("BadMinutes", ctx.config().parserMaxTargetMinutes()));
            return;
        }
        int targetMinutes = parsed.getAsInt();
        minutes.getValueFactory().setValue(targetMinutes);
        int chosenMinutes = targetMinutes;
        double ratio = familiarFromGoal.isSelected() ? Double.NaN : familiar.getValue();
        String playlistName = name.getText() == null || name.getText().isBlank() ? null : name.getText().strip();
        PlaylistSpec flags = new PlaylistSpec(chosenMinutes, allowExplicit.isSelected(), ratio, List.of(), playlistName);
        result.getChildren().clear();
        ctx.run(status, "ActionCreate", format.w("Creating"), () -> {
            ctx.controller().setProgressListener(state -> Platform.runLater(() -> status.busy(format.progress(state))));
            Playlist playlist = ctx.controller().createPlaylist(new AgentRequest(text, RequestKind.CREATE_PLAYLIST, flags));
            AgentSession session = ctx.controller().lastSession();
            return new Created(playlist, session == null ? List.of() : session.notices(),
                    session == null ? null : session.explanation());
        }, this::show, create, goal, minutes, allowExplicit, familiarFromGoal, familiar, name);
    }

    private void show(Created created) {
        Playlist playlist = created.playlist();
        status.info(format.w("Created", playlist.name(), playlist.tracks().size()));
        ListView<String> tracks = new ListView<>(FXCollections.observableArrayList(playlist.tracks().stream()
                .map(Track::title).toList()));
        tracks.setPrefHeight(TRACKS_HEIGHT);
        result.getChildren().setAll(tracks);
        created.notices().forEach(n -> result.getChildren().add(UiParts.muted(n)));
        result.getChildren().add(WhyExpander.of(format, created.explanation()));
        dialog.getDialogPane().getScene().getWindow().sizeToScene();
        onCreated.accept(playlist);
    }
}
