package com.musicmind.app.view;

import com.musicmind.domain.Verdict;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.Locale;

/** Thumbs up / thumbs down for one track (F9); used on recommendations and on playlist tracks. */
final class FeedbackBar extends HBox {

    FeedbackBar(GuiContext ctx, StatusLine status, String trackId, String title) {
        super(UiParts.GAP);
        Button accept = button(ctx.format().w("Accept"), ctx.format().w("AcceptTip"));
        Button reject = button(ctx.format().w("Reject"), ctx.format().w("RejectTip"));
        accept.setOnAction(e -> send(ctx, status, trackId, title, Verdict.ACCEPT, accept, reject));
        reject.setOnAction(e -> send(ctx, status, trackId, title, Verdict.REJECT, accept, reject));
        getChildren().addAll(accept, reject);
    }

    private static Button button(String label, String tip) {
        Button button = new Button(label);
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    private static void send(GuiContext ctx, StatusLine status, String trackId, String title, Verdict verdict,
                             Button accept, Button reject) {
        ctx.run(status, "record your feedback", ctx.format().w("Working"), () -> {
            ctx.controller().recordFeedback(trackId, verdict);
            return ctx.controller().memory().warning();
        }, warning -> {
            if (warning == null) {
                status.info(ctx.format().w("Recorded", verdict.name().toLowerCase(Locale.ROOT), title));
            } else {
                status.error(ctx.format().config().wording("cliNotSaved").formatted(warning));
            }
        }, accept, reject);
    }
}
