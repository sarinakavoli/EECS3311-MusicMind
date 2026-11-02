package com.musicmind.app.view;

import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

import java.util.function.Supplier;

/** The "Why these?" expander (F10): collapsed until opened, then shows the reasons. */
final class WhyExpander {

    private WhyExpander() {
    }

    /** An expander whose text is already known (a recommendation's reason). */
    static TitledPane of(ViewFormat format, String text) {
        Label body = UiParts.wrapped(text == null || text.isBlank() ? format.w("WhyUnavailable") : text);
        TitledPane pane = new TitledPane(format.w("WhyThese"), body);
        pane.setExpanded(false);
        return pane;
    }

    /** An expander that asks for its text the first time it is opened (a playlist's explanation). */
    static TitledPane lazy(GuiContext ctx, String action, Supplier<String> loader) {
        StatusLine status = new StatusLine();
        Label body = UiParts.wrapped("");
        TitledPane pane = new TitledPane(ctx.format().w("WhyThese"), new VBox(UiParts.GAP, status, body));
        pane.setExpanded(false);
        boolean[] requested = {false};
        pane.expandedProperty().addListener((observable, was, expanded) -> {
            if (!expanded || requested[0]) {
                return;
            }
            requested[0] = true;
            ctx.run(status, action, ctx.format().w("Working"), loader, text -> body.setText(text == null || text.isBlank()
                    ? ctx.format().w("WhyUnavailable") : text));
        });
        return pane;
    }
}
