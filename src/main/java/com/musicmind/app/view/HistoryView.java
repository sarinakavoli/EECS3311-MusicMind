package com.musicmind.app.view;

import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.User;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** F12: past sessions (opening one shows its plan and result) and saved playlist versions with a "Restore" action. */
public final class HistoryView implements TabView {

    private record Loaded(List<AgentSession> sessions, List<SnapshotInfo> snapshots, String warning) { }

    /** What a restore did; {@code missing} is set when the Spotify playlist was deleted and can be recreated. */
    private record Outcome(String message, String missing) { }

    private static final double TABLE_HEIGHT = 220;
    private static final double LIST_HEIGHT = 160;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final Button refresh;
    private final StatusLine status = new StatusLine();
    private final TableView<ViewFormat.HistoryRow> sessions = new TableView<>();
    private final VBox sessionDetail = new VBox(2);
    private final ListView<SnapshotInfo> versions = new ListView<>();
    private final Node root;

    public HistoryView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        refresh = new Button(format.w("Refresh"));
        refresh.setOnAction(e -> load(ctx.userProperty().get()));
        ctx.onDataChanged(() -> {
            if (ctx.userProperty().get() != null) {
                load(ctx.userProperty().get());
            }
        });

        sessions.getColumns().add(column(format.w("ColWhen"), ViewFormat.HistoryRow::when));
        sessions.getColumns().add(column(format.w("ColStatus"), ViewFormat.HistoryRow::status));
        sessions.getColumns().add(column(format.w("ColRequest"), ViewFormat.HistoryRow::request));
        sessions.getColumns().add(column(format.w("ColPlaylist"), ViewFormat.HistoryRow::playlistId));
        sessions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sessions.setPrefHeight(TABLE_HEIGHT);
        sessions.setPlaceholder(new Label(ctx.config().wording("cliNone")));
        sessions.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null) {
                openSession(now.sessionId());
            }
        });
        sessionDetail.getChildren().add(UiParts.muted(format.w("SelectSession")));

        versions.setPrefHeight(LIST_HEIGHT);
        versions.setPlaceholder(new Label(ctx.config().wording("cliNone")));
        versions.setCellFactory(list -> new VersionCell());

        VBox page = UiParts.page(format.w("Menu.history"), format.w("Desc.history"), "", new HBox(UiParts.GAP, refresh), status, UiParts.heading(format.w("HistoryTitle")), sessions,
                sessionDetail, UiParts.heading(format.w("SavedVersions")), versions);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        root = new LoginGate(ctx, scroll, false);
    }

    private static TableColumn<ViewFormat.HistoryRow, String> column(String title,
            java.util.function.Function<ViewFormat.HistoryRow, String> value) {
        TableColumn<ViewFormat.HistoryRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    @Override
    public String title() {
        return format.w("Menu.history");
    }

    @Override
    public String description() {
        return format.w("Desc.history");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        load(user);
    }

    private void load(User user) {
        ctx.run(status, "ActionHistory", format.w("Working"), () -> {
            List<AgentSession> past = ctx.controller().getHistory(user);
            List<SnapshotInfo> snapshots = ctx.controller().getSnapshots(user);
            return new Loaded(past, snapshots, ctx.controller().memory().warning());
        }, loaded -> {
            sessions.setItems(FXCollections.observableArrayList(format.historyRows(loaded.sessions())));
            versions.setItems(FXCollections.observableArrayList(loaded.snapshots()));
            if (loaded.warning() != null) {
                status.info(format.w("HistoryIncomplete"));
            }
        }, refresh);
    }

    private void openSession(String id) {
        ctx.run(status, "ActionSession", format.w("Working"), () -> ctx.controller().getSession(id), session -> {
            sessionDetail.getChildren().setAll(format.sessionLines(session).stream().map(UiParts::wrapped).toList());
        });
    }

    private void restore(SnapshotInfo version, Button button) {
        ctx.run(status, "ActionRestore", format.w("Restoring"), () -> {
            try {
                Playlist restored = ctx.controller().restore(version.id());
                return new Outcome(ctx.controller().lastRestoreChanged()
                        ? format.w("Restored", restored.name(), restored.tracks().size())
                        : format.w("RestoredUnchanged", restored.name()), null);
            } catch (PlaylistMissingException e) {
                return new Outcome(null, e.getMessage());
            }
        }, outcome -> {
            if (outcome.missing() == null) {
                status.info(outcome.message());
                ctx.dataChanged();
            } else if (confirmRecreate(outcome.missing())) {
                recreate(version, button);
            } else {
                status.info(outcome.missing());
            }
        }, button);
    }

    private boolean confirmRecreate(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message + "\n\n" + format.w("RecreatePrompt"),
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText(format.w("RecreateTitle"));
        UiParts.dark(alert.getDialogPane());
        return alert.showAndWait().filter(ButtonType.YES::equals).isPresent();
    }

    private void recreate(SnapshotInfo version, Button button) {
        ctx.run(status, "ActionRecreate", format.w("Restoring"), () -> {
            Playlist created = ctx.controller().recreateFromSnapshot(version.id());
            return format.w("Recreated", created.name(), created.tracks().size());
        }, message -> {
            status.info(message);
            ctx.dataChanged();
        }, button);
    }

    /** A saved version with its Restore button. */
    private final class VersionCell extends ListCell<SnapshotInfo> {
        private final Label text = new Label();
        private final Button restore = new Button(format.w("Restore"));
        private final HBox row;

        VersionCell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row = new HBox(UiParts.GAP, text, spacer, restore);
            row.setAlignment(Pos.CENTER_LEFT);
            restore.setOnAction(e -> {
                if (getItem() != null) {
                    restore(getItem(), restore);
                }
            });
        }

        @Override
        protected void updateItem(SnapshotInfo item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            text.setText(format.snapshotLine(item));
            setGraphic(row);
        }
    }
}
