package com.musicmind.app.view;

import com.musicmind.domain.Track;
import com.musicmind.domain.Verdict;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * One song as a row: the title with the artist names under it (muted), the length, and small icons. The
 * like and not-for-me icons record F9 feedback through the controller; the one chosen stays highlighted.
 * Add, Block and Remove call the handlers in {@link Options}.
 */
final class TrackRow {

    /** What a row shows beside the song. A null handler hides its button. */
    record Options(boolean feedback, Consumer<Track> onAdd, Consumer<Track> onBlock, Consumer<Track> onRemove) {

        /** Only the like and not-for-me icons. */
        static Options feedbackOnly() {
            return new Options(true, null, null, null);
        }

        /** No icons at all. */
        static Options plain() {
            return new Options(false, null, null, null);
        }

        Options withAdd(Consumer<Track> handler) {
            return new Options(feedback, handler, onBlock, onRemove);
        }

        Options withBlock(Consumer<Track> handler) {
            return new Options(feedback, onAdd, handler, onRemove);
        }

        Options withRemove(Consumer<Track> handler) {
            return new Options(feedback, onAdd, onBlock, handler);
        }
    }

    private TrackRow() {
    }

    static Node of(GuiContext ctx, StatusLine status, Track track, Options options) {
        ViewFormat format = ctx.format();
        Label title = new Label(track.title() + (track.isExplicit() ? "  [" + format.w("ExplicitTag") + "]" : ""));
        title.getStyleClass().add("mm-track-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(Double.MAX_VALUE);
        VBox text = new VBox(2, title);
        String artists = format.artists(track);
        if (!artists.isEmpty()) {
            Label line = new Label(artists);
            line.getStyleClass().addAll("mm-muted", "mm-track-artists");
            line.setTextOverrun(OverrunStyle.ELLIPSIS);
            line.setMaxWidth(Double.MAX_VALUE);
            text.getChildren().add(line);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label length = UiParts.muted(format.duration(track.durationMs()));
        length.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(UiParts.GAP, text, length);
        row.getStyleClass().add("mm-row");
        row.setAlignment(Pos.CENTER_LEFT);

        if (options.feedback()) {
            Button like = icon(format.w("LikeIcon"), format.w("LikeTip"));
            Button notForMe = icon(format.w("NotForMeIcon"), format.w("NotForMeTip"));
            like.setOnAction(e -> send(ctx, status, track, Verdict.ACCEPT, like, notForMe));
            notForMe.setOnAction(e -> send(ctx, status, track, Verdict.REJECT, like, notForMe));
            row.getChildren().addAll(like, notForMe);
        }
        action(row, format.w("AddButton"), "mm-primary", options.onAdd(), track);
        action(row, format.w("BlockButton"), null, options.onBlock(), track);
        action(row, format.w("RemoveButton"), null, options.onRemove(), track);
        return row;
    }

    private static void action(HBox row, String label, String styleClass, Consumer<Track> handler, Track track) {
        if (handler == null) {
            return;
        }
        Button button = new Button(label);
        if (styleClass != null) {
            button.getStyleClass().add(styleClass);
        }
        button.setOnAction(e -> handler.accept(track));
        row.getChildren().add(button);
    }

    private static Button icon(String glyph, String tip) {
        Button button = new Button(glyph);
        button.getStyleClass().add("mm-icon-button");
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    private static void send(GuiContext ctx, StatusLine status, Track track, Verdict verdict, Button like, Button notForMe) {
        ctx.run(status, "ActionFeedback", ctx.format().w("Working"), () -> {
            ctx.controller().recordFeedback(track.id(), verdict);
            return ctx.controller().memory().warning();
        }, warning -> {
            if (warning == null) {
                choose(verdict == Verdict.ACCEPT ? like : notForMe, verdict == Verdict.ACCEPT ? notForMe : like);
                status.info(ctx.format().w("Recorded", verdict.name().toLowerCase(Locale.ROOT), track.title()));
            } else {
                status.error(ctx.format().config().wording("cliNotSaved").formatted(warning));
            }
        }, like, notForMe);
    }

    /** Highlights the chosen icon and clears the other. */
    private static void choose(Button on, Button off) {
        off.getStyleClass().remove("mm-icon-on");
        if (!on.getStyleClass().contains("mm-icon-on")) {
            on.getStyleClass().add("mm-icon-on");
        }
    }
}
