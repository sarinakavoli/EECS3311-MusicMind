package com.musicmind.app;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

/**
 * JavaFX GUI entry point. The views are built over the same AppController /
 * MusicAgentFacade used by the CLI (MVC), so both interfaces expose the same
 * functionality without duplicated logic. Feature views are wired in Phase 1+.
 */
public class GuiApp extends Application {

    @Override
    public void start(Stage stage) {
        Label title = new Label("MusicMind");
        title.setFont(Font.font(28));
        Label subtitle = new Label("AI Music Discovery & Playlist Management Agent");
        Label note = new Label("GUI scaffold — feature views are being wired in (Phase 1).");

        VBox root = new VBox(12, title, subtitle, note);
        root.setPadding(new Insets(24));

        stage.setScene(new Scene(root, 680, 420));
        stage.setTitle("MusicMind");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
