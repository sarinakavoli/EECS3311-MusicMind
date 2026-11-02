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
import javafx.scene.control.Button;
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
                          String validationNote, String change) { }

    private static final double TRACK_AREA_HEIGHT = 300;
    private static final double LIST_WIDTH = 260;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final ListView<PlaylistSummary> playlists = new ListView<>();
    private final Button refresh;
    private final Button create;
    private final StatusLine status = new StatusLine();
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
        create.setOnAction(e -> new CreatePlaylistDialog(ctx, created -> {
            announceChange();
            loadList(created.spotifyId());
        }).show());
        playlists.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PlaylistSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.w("PlaylistLine", item.name(), item.trackCount()));
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
        VBox left = new VBox(UiParts.GAP, new HBox(UiParts.GAP, refresh, create), playlists);
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
        split.setDividerPositions(0.3);
        root = new LoginGate(ctx, split, false);
    }

    @Override
    public String title() {
        return format.w("TabPlaylists");
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
        ctx.run(status, "ActionListPlaylists", format.w("Working"), () -> ctx.controller().getPlaylists(), list -> {
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
        return new Opened(playlist, all, validation, target, note, change);
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
            tracks.getChildren().add(row(track));
        }
        notices.getChildren().setAll(opened.notices().stream().map(UiParts::muted).toList());
        String shownId = openId;
        why.getChildren().setAll(WhyExpander.lazy(ctx, "ActionExplain", () -> ctx.controller().explain(shownId)));
    }

    private HBox row(Track track) {
        Label name = new Label(track.title() + (track.isExplicit() ? "  [" + format.w("ExplicitTag") + "]" : ""));
        Label length = UiParts.muted(format.duration(track.durationMs()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(UiParts.GAP, name, spacer, length, new FeedbackBar(ctx, feedbackStatus, track.id(), track.title()));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
