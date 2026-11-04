package com.musicmind.app.view;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * Wraps a tab's content so that, until the user is logged in, it is disabled and a prompt covers it. A tab that
 * must work without Spotify (Preferences) opens up when Spotify is not configured.
 */
final class LoginGate extends StackPane {

    LoginGate(GuiContext ctx, Node content, boolean worksWithoutSpotify) {
        Label prompt = new Label();
        prompt.setWrapText(true);
        prompt.getStyleClass().add("mm-gate-prompt");
        StackPane.setAlignment(prompt, Pos.CENTER);
        boolean configured = ctx.spotifyConfigured();
        prompt.setText(configured ? ctx.format().w("LoginFirst") : ctx.format().w("NotConfiguredGate"));
        BooleanBinding locked = worksWithoutSpotify && !configured
                ? Bindings.createBooleanBinding(() -> false)
                : ctx.userProperty().isNull();
        content.disableProperty().bind(locked);
        prompt.visibleProperty().bind(locked);
        prompt.setMaxWidth(420);
        getChildren().addAll(content, prompt);
    }
}
