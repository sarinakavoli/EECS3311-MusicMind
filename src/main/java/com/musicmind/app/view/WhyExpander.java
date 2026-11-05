package com.musicmind.app.view;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** The "Why these?" / "Why this song?" expander (F10): collapsed until opened, then shows the reasons. */
final class WhyExpander {

    /** Tall enough for a few sentences; longer text scrolls. */
    private static final double MAX_HEIGHT = 90;

    private WhyExpander() {
    }

    /** An expander whose text is already known (a recommendation's reason), titled "Why these?". */
    static TitledPane of(ViewFormat format, String text) {
        return of(format, "WhyThese", text);
    }

    /** The same, with the title taken from {@code text.gui<titleKey>} ("WhyThisSong" for one song). */
    static TitledPane of(ViewFormat format, String titleKey, String text) {
        Label body = UiParts.wrapped(text == null || text.isBlank() ? format.w("WhyUnavailable") : text);
        body.getStyleClass().add("mm-why-text");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(MAX_HEIGHT);
        scroll.setMaxHeight(MAX_HEIGHT);
        scroll.getStyleClass().add("mm-why-scroll");
        TitledPane pane = new TitledPane(format.w(titleKey), scroll);
        pane.setExpanded(false);
        return pane;
    }

    /** An expander that asks for its text the first time it is opened (a playlist's explanation). */
    static TitledPane lazy(GuiContext ctx, String action, Supplier<String> loader) {
        StatusLine status = new StatusLine();
        Label body = UiParts.wrapped("");
        body.getStyleClass().add("mm-why-text");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(MAX_HEIGHT);
        scroll.setMaxHeight(MAX_HEIGHT);
        scroll.getStyleClass().add("mm-why-scroll");
        TitledPane pane = new TitledPane(ctx.format().w("WhyThese"), new VBox(UiParts.SPACE_S, status, scroll));
        pane.setExpanded(false);
        AtomicBoolean requested = new AtomicBoolean(false);
        pane.expandedProperty().addListener((observable, was, expanded) -> {
            if (!expanded || !requested.compareAndSet(false, true)) {
                return;
            }
            Supplier<String> retryable = () -> {
                try {
                    return loader.get();
                } catch (RuntimeException | Error e) {
                    requested.set(false);   // reopening the expander tries again
                    throw e;
                }
            };
            ctx.run(status, action, ctx.format().w("Working"), retryable, text -> body.setText(text == null || text.isBlank()
                    ? ctx.format().w("WhyUnavailable") : text));
        });
        return pane;
    }
}
