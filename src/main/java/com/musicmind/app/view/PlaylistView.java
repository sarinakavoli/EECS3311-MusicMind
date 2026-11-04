package com.musicmind.app.view;

import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import com.musicmind.domain.ValidationResult;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.RateLimitException;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * F6, F7 and F10 on a playlist: the user's playlists, an opened playlist with its tracks (thumbs up / down on
 * each), validation badges and warnings, a "Modify" box, "Undo", and a "Why these?" expander. "Create playlist"
 * opens the F5 dialog.
 */
public final class PlaylistView implements TabView {

    /** An opened playlist with everything the view shows about it, read on the worker. */
    private record Opened(Playlist playlist, List<String> notices, ValidationResult validation, int targetMinutes,
                          String validationNote, String change,
                          java.util.Map<String, com.musicmind.domain.Verdict> verdicts) { }

    private static final double TRACK_AREA_HEIGHT = 300;
    private static final double LIST_WIDTH = 330;
    /** Room the list cell keeps around its row: its own padding and the scroll bar. */
    private static final double ROW_INSET = 28;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final ListView<PlaylistSummary> playlists = new ListView<>();
    private final Button refresh;
    private final Button create;
    private final StatusLine status = new StatusLine();
    private final StatusLine listStatus = new StatusLine();
    private final StatusLine feedbackStatus = new StatusLine();
    private final Label title = UiParts.heading("");
    private final FlowPane badges = new FlowPane(UiParts.GAP, UiParts.GAP);
    private final VBox warnings = new VBox(2);
    private final VBox tracks = new VBox(2);
    private final VBox notices = new VBox(2);
    private final VBox why = new VBox();
    private final TextField instruction = new TextField();
    private final Button modify;
    private final Button undo;
    private final Button check;
    private final VBox detail = new VBox(UiParts.GAP);
    private final Node root;
    private String openId;
    private boolean quietSelection;
    private boolean changedHere;

    public PlaylistView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        refresh = new Button(format.w("Refresh"));
        refresh.setOnAction(e -> loadList(null));
        ctx.onDataChanged(this::reloadAfterChange);
        create = new Button(format.w("CreatePlaylist"));
        create.getStyleClass().add("mm-primary");
        create.setOnAction(e -> new CreatePlaylistDialog(ctx, created -> {
            announceChange();
            loadList(created.spotifyId());
        }).show(create.getScene() == null ? null : create.getScene().getWindow()));
        playlists.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PlaylistSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setPrefWidth(0);   // the row fits the list; a long name is cut with an ellipsis, never scrolled
                Node row = empty || item == null ? null : playlistRow(item);
                if (row instanceof HBox box) {
                    box.prefWidthProperty().bind(widthProperty().subtract(ROW_INSET));
                }
                setGraphic(row);
            }
        });
        playlists.setPlaceholder(new Label(ctx.config().wording("cliNoPlaylists")));
        playlists.setPrefWidth(LIST_WIDTH);
        playlists.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null && !quietSelection) {
                open(now.id());
            }
        });

        instruction.setPromptText(format.w("ModifyPrompt"));
        HBox.setHgrow(instruction, Priority.ALWAYS);
        instruction.setOnAction(e -> modify());
        modify = new Button(format.w("Modify"));
        modify.setOnAction(e -> modify());
        undo = new Button(format.w("Undo"));
        undo.setOnAction(e -> undo());
        check = new Button(format.w("CheckPlaylist"));
        check.setOnAction(e -> open(openId));
        HBox modifyBox = new HBox(UiParts.GAP, instruction, modify, undo, check);
        modifyBox.setAlignment(Pos.CENTER_LEFT);

        ScrollPane trackScroll = new ScrollPane(tracks);
        trackScroll.setFitToWidth(true);
        trackScroll.setPrefHeight(TRACK_AREA_HEIGHT);
        detail.getChildren().addAll(title, badges, warnings, trackScroll, feedbackStatus, modifyBox, notices, why);
        detail.setPadding(new Insets(UiParts.PAD));
        detail.setVisible(false);
        Label chooser = UiParts.muted(format.w("SelectPlaylist"));
        VBox left = new VBox(UiParts.GAP, new HBox(UiParts.GAP, refresh, create), playlists, listStatus);
        left.setPadding(new Insets(UiParts.PAD));
        VBox.setVgrow(playlists, Priority.ALWAYS);
        VBox right = new VBox(UiParts.GAP, status, chooser, detail);
        right.setPadding(new Insets(UiParts.PAD));
        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.setFitToWidth(true);
        chooser.visibleProperty().bind(detail.visibleProperty().not());
        chooser.managedProperty().bind(chooser.visibleProperty());
        detail.managedProperty().bind(detail.visibleProperty());
        SplitPane split = new SplitPane(left, rightScroll);
        split.setDividerPositions(0.38);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox page = UiParts.page(format.w("Menu.playlists"), format.w("Desc.playlists"), format.w("Example.playlists"), split);
        page.setFillWidth(true);
        root = new LoginGate(ctx, page, false);
    }

    @Override
    public String title() {
        return format.w("Menu.playlists");
    }

    @Override
    public String description() {
        return format.w("Desc.playlists");
    }

    @Override
    public String example() {
        return format.w("Example.playlists");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        loadList(null);
    }

    /**
     * Reads the playlist list and selects {@code selectId} afterwards when given. {@code openIt} says whether
     * selecting it opens it (a new or changed playlist) or only highlights it (the one already shown).
     */
    private void loadList(String selectId, boolean openIt) {
        ctx.run(listStatus, "ActionListPlaylists", format.w("Working"), () -> ctx.controller().getPlaylists(), list -> {
            quietSelection = !openIt;
            try {
                playlists.setItems(FXCollections.observableArrayList(list));
                if (selectId != null) {
                    list.stream().filter(p -> p.id().equals(selectId)).findFirst()
                            .ifPresent(p -> playlists.getSelectionModel().select(p));
                }
            } finally {
                quietSelection = false;
            }
        }, refresh, create);
    }

    private void loadList(String selectId) {
        loadList(selectId, true);
    }

    /** Other tabs changed playlists: reload the list and open the shown playlist again. */
    private void reloadAfterChange() {
        if (!changedHere && ctx.userProperty().get() != null) {
            loadList(openId, true);
        }
    }

    /** Tells the other tabs that this view changed a playlist; this view does not reload itself for it. */
    private void announceChange() {
        changedHere = true;
        try {
            ctx.dataChanged();
        } finally {
            changedHere = false;
        }
    }

    private void open(String id) {
        if (id == null) {
            return;
        }
        if (!id.equals(openId)) {
            openId = null;   // a failed open must not leave Modify and Undo aimed at the previous playlist
            detail.setVisible(false);
        }
        ctx.run(status, "ActionShowPlaylist", format.w("Opening"), () -> read(id, null, List.of()), this::show,
                modify, undo, check, instruction);
    }

    /** What a write did, and the refreshed view of the playlist, or why it could not be read back. */
    private record Changed(Opened opened, String change, Throwable refreshFailure) { }

    /** The words of a successful write and the notices it left. */
    private record Written(String change, List<String> notices) { }

    private void modify() {
        String text = instruction.getText() == null ? "" : instruction.getText().strip();
        if (openId == null || text.isEmpty()) {
            return;
        }
        String id = openId;
        change(id, "ActionModify", format.w("Modifying"), () -> {
            ctx.controller().modifyPlaylist(id, text);
            return new Written(ctx.config().wording("cliChangedPrefix") + " " + ctx.controller().lastChangeSummary(),
                    ctx.controller().lastModifyNotices());
        }, true);
    }

    private void undo() {
        if (openId == null) {
            return;
        }
        String id = openId;
        change(id, "ActionUndo", format.w("Undoing"), () -> {
            ctx.controller().undo(id);
            return new Written(ctx.config().wording("cliUndone"), List.of());
        }, false);
    }

    /**
     * Writes to a playlist, then reads it back. A failed read-back is not a failed write: the success still
     * shows, so the user does not repeat the change. An expired login during the read-back still logs out.
     */
    private void change(String id, String action, String busy, java.util.function.Supplier<Written> write, boolean clearInstruction) {
        ctx.run(status, action, busy, () -> {
            Written written = write.get();
            try {
                return new Changed(read(id, written.change(), written.notices()), written.change(), null);
            } catch (RuntimeException e) {
                return new Changed(null, written.change(), e);
            }
        }, changed -> {
            if (clearInstruction) {
                instruction.clear();
            }
            if (changed.opened() != null) {
                show(changed.opened());
            } else {
                status.info(changed.change());
                notices.getChildren().setAll(UiParts.muted(format.w("RefreshFailed")));
                if (changed.refreshFailure() instanceof AuthExpiredException) {
                    ctx.userProperty().set(null);
                }
            }
            announceChange();
            loadList(openId, false);
        }, modify, undo, check, instruction);
    }

    /** Runs on the worker: the playlist, then its validation; a validation that cannot run does not hide the playlist. */
    private Opened read(String id, String change, List<String> extraNotices) {
        Playlist playlist = ctx.controller().getPlaylist(id);
        List<String> all = new ArrayList<>(extraNotices);
        all.addAll(ctx.controller().lastPlaylistNotices());
        ValidationResult validation = null;
        int target = 0;
        String note = null;
        try {
            validation = ctx.controller().validatePlaylist(id);
            target = ctx.controller().lastValidationTargetMinutes();
            note = ctx.controller().lastValidationNote();
        } catch (AuthExpiredException | RateLimitException e) {
            throw e;
        } catch (RuntimeException e) {
            all.add(ctx.format().failure("ActionCheck", e));
        }
        return new Opened(playlist, all, validation, target, note, change, ctx.controller().feedbackFor());
    }

    private void show(Opened opened) {
        Playlist playlist = opened.playlist();
        openId = playlist.spotifyId();
        detail.setVisible(true);
        title.setText(format.playlistTitle(playlist));
        status.info(opened.change() == null ? "" : opened.change());

        badges.getChildren().clear();
        warnings.getChildren().clear();
        if (opened.validation() != null) {
            format.badges(opened.validation(), playlist.tracks(), opened.targetMinutes())
                    .forEach(b -> badges.getChildren().add(UiParts.badge(b)));
            opened.validation().violations().forEach(v -> warnings.getChildren().add(UiParts.wrapped(v)));
            if (opened.validationNote() != null) {
                warnings.getChildren().add(UiParts.muted(opened.validationNote()));
            }
        }

        tracks.getChildren().clear();
        if (playlist.tracks().isEmpty()) {
            tracks.getChildren().add(UiParts.muted(format.w("NoTracks")));
        }
        for (Track track : playlist.tracks()) {
            tracks.getChildren().add(row(track, opened.verdicts()));
        }
        notices.getChildren().setAll(opened.notices().stream().map(UiParts::muted).toList());
        String shownId = openId;
        why.getChildren().setAll(WhyExpander.lazy(ctx, "ActionExplain", () -> ctx.controller().explain(shownId)));
    }

    private Node row(Track track, java.util.Map<String, com.musicmind.domain.Verdict> verdicts) {
        return TrackRow.of(ctx, feedbackStatus, track, TrackRow.Options.feedbackOnly().withBlock(this::block),
                verdicts.get(track.id()));
    }

    /** Blocks one song for future playlists, then checks the open playlist again so its badges show the block. */
    private void block(Track track) {
        String shownId = openId;
        ctx.run(feedbackStatus, "ActionBlock", format.w("Blocking"), () -> {
            ctx.controller().blockTrack(track.id());
            return track.title();
        }, title -> {
            ctx.dataChanged();
            open(shownId);
            feedbackStatus.info(format.w("BlockedSongInPlaylist", title));
        });
    }

    /** One playlist in the list: its name and size, with Open and Delete. */
    private Node playlistRow(PlaylistSummary item) {
        Label name = new Label(item.name());
        name.getStyleClass().add("mm-track-title");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(Double.MAX_VALUE);
        Label count = new Label(format.w("PlaylistCount", item.trackCount()));
        count.getStyleClass().addAll("mm-muted", "mm-track-artists");
        VBox text = new VBox(2, name, count);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button open = new Button(format.w("OpenPlaylist"));
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(e -> open(item.id()));
        Button delete = new Button(format.w("DeletePlaylist"));
        delete.getStyleClass().add("mm-danger");
        delete.setMinWidth(Region.USE_PREF_SIZE);
        delete.setOnAction(e -> confirmDelete(item));
        HBox row = new HBox(UiParts.GAP, text, open, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void confirmDelete(PlaylistSummary item) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        ButtonType yes = new ButtonType(format.w("DeleteYes"), ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType(format.w("DeleteNo"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(yes, no);
        alert.setTitle(format.w("DeleteTitle"));
        alert.setHeaderText(format.w("DeleteTitle"));
        alert.setContentText(format.w("DeleteConfirm", item.name()));
        UiParts.dark(alert.getDialogPane());
        if (root.getScene() != null) {
            alert.initOwner(root.getScene().getWindow());
        }
        if (alert.showAndWait().orElse(no) != yes) {
            return;
        }
        ctx.run(listStatus, "ActionDelete", format.w("Deleting"), () -> {
            ctx.controller().deletePlaylist(item.id());
            return item.name();
        }, name -> {
            if (item.id().equals(openId)) {
                openId = null;
                detail.setVisible(false);
            }
            announceChange();
            loadList(null, false);
            listStatus.info(format.w("Deleted", name));
        }, refresh, create);
    }
}
