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
    record Options(boolean feedback, Consumer<Track> onAdd, Consumer<Track> onBlock, Consumer<Track> onRemove,
                   boolean play) {

        /** Only the like and not-for-me icons. */
        static Options feedbackOnly() {
            return new Options(true, null, null, null, false);
        }

        /** No icons at all. */
        static Options plain() {
            return new Options(false, null, null, null, false);
        }

        Options withAdd(Consumer<Track> handler) {
            return new Options(feedback, handler, onBlock, onRemove, play);
        }

        Options withBlock(Consumer<Track> handler) {
            return new Options(feedback, onAdd, handler, onRemove, play);
        }

        Options withRemove(Consumer<Track> handler) {
            return new Options(feedback, onAdd, onBlock, handler, play);
        }

        /** Adds a small "Play in Spotify" button that opens the song in the user's Spotify app or web player. */
        Options withPlay() {
            return new Options(feedback, onAdd, onBlock, onRemove, true);
        }
    }

    private static final String ON_CLASS = "mm-on-class";

    private TrackRow() {
    }

    /** @param chosen the verdict already given to this song (shown highlighted), or null */
    static Node of(GuiContext ctx, StatusLine status, Track track, Options options, Verdict chosen) {
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
            Button like = icon(format.w("IconLabel", format.w("LikeIcon"), format.w("LikeCaption")), format.w("LikeTip"), "mm-icon-on");
            Button notForMe = icon(format.w("IconLabel", format.w("NotForMeIcon"), format.w("NotForMeCaption")),
                    format.w("NotForMeTip"), "mm-icon-off-on");
            if (chosen != null) {
                choose(chosen == Verdict.ACCEPT ? like : notForMe, chosen == Verdict.ACCEPT ? notForMe : like);
            }
            like.setOnAction(e -> send(ctx, status, track, Verdict.ACCEPT, like, notForMe));
            notForMe.setOnAction(e -> send(ctx, status, track, Verdict.REJECT, like, notForMe));
            row.getChildren().addAll(like, notForMe);
        }
        if (options.play() && format.spotifyTrackUrl(track) != null) {
            Button play = new Button(format.w("PlayButton"));
            play.getStyleClass().add("mm-play-button");
            play.setMinWidth(Region.USE_PREF_SIZE);
            play.setTooltip(new Tooltip(format.w("PlayTip")));
            play.setAccessibleText(format.w("PlayTip"));
            play.setOnAction(e -> {
                if (!ctx.openInSpotify(track)) {
                    status.error(format.w("PlayFailed", track.title()));
                }
            });
            row.getChildren().add(play);
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
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> handler.accept(track));
        row.getChildren().add(button);
    }

    private static Button icon(String label, String tip, String onClass) {
        Button button = new Button(label);
        button.getProperties().put(ON_CLASS, onClass);
        button.setAccessibleText(tip);
        button.setMinWidth(Region.USE_PREF_SIZE);
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

    /** Highlights the chosen icon (green for like, amber for not for me) and clears the other. */
    private static void choose(Button on, Button off) {
        off.getStyleClass().removeAll("mm-icon-on", "mm-icon-off-on");
        String onClass = (String) on.getProperties().get(ON_CLASS);
        if (!on.getStyleClass().contains(onClass)) {
            on.getStyleClass().add(onClass);
        }
    }
}
