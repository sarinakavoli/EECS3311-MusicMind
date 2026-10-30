package com.musicmind.app;

import com.musicmind.controller.AppController;
import com.musicmind.controller.TasteProfileResult;
import com.musicmind.domain.Artist;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JavaFX GUI entry point. The view is built over the same {@link AppController}
 * used by the CLI (MVC), so both interfaces expose the same functionality
 * without duplicated logic. This screen implements F1 — View Taste Profile.
 */
public class GuiApp extends Application {

    private AppController controller;

    private final ComboBox<String> timeRange = new ComboBox<>(
            FXCollections.observableArrayList("short", "medium", "long"));
    private final Button viewButton = new Button("View Taste Profile");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label status = new Label();
    private final Label summary = new Label();
    private final Label artists = new Label();
    private final ListView<String> genres = new ListView<>();

    @Override
    public void start(Stage stage) {
        controller = AppController.create();

        Label title = new Label("MusicMind");
        title.setFont(Font.font(28));
        Label subtitle = new Label("AI Music Discovery & Playlist Management Agent");

        timeRange.getSelectionModel().select("medium");
        spinner.setVisible(false);
        spinner.setPrefSize(20, 20);
        viewButton.setDefaultButton(true);
        viewButton.setOnAction(e -> onViewProfile());

        HBox controls = new HBox(10, new Label("Time range:"), timeRange, viewButton, spinner);
        controls.setAlignment(Pos.CENTER_LEFT);

        status.setWrapText(true);
        summary.setWrapText(true);
        summary.setFont(Font.font(14));
        artists.setWrapText(true);
        genres.setPrefHeight(220);
        VBox.setVgrow(genres, Priority.ALWAYS);

        Label genresHeading = new Label("Top genres");
        genresHeading.setFont(Font.font(15));
        Label artistsHeading = new Label("Top artists");
        artistsHeading.setFont(Font.font(15));

        VBox root = new VBox(12, title, subtitle, controls, status,
                genresHeading, genres, artistsHeading, artists, new Label("Summary"), summary);
        root.setPadding(new Insets(24));

        if (!controller.isSpotifyConfigured()) {
            viewButton.setDisable(true);
            status.setText("Spotify is not configured. Copy .env.example to .env and set "
                    + "SPOTIFY_CLIENT_ID, then restart. (You can still browse the UI.)");
        } else {
            status.setText("Choose a time range and click “View Taste Profile”. "
                    + "The first run opens your browser to connect Spotify.");
        }

        stage.setScene(new Scene(root, 720, 640));
        stage.setTitle("MusicMind");
        stage.show();
    }

    private void onViewProfile() {
        String range = timeRange.getValue();
        setBusy(true, "Building your taste profile over the “" + range + "” term…");

        Thread worker = new Thread(() -> {
            try {
                TasteProfileResult result = controller.viewTasteProfile(range);
                Platform.runLater(() -> {
                    showResult(result);
                    setBusy(false, "Done — saved to memory.");
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() -> setBusy(false, "Could not build your profile: " + ex.getMessage()));
            }
        }, "taste-profile-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void showResult(TasteProfileResult result) {
        List<String> rows = result.rankedGenres(10).stream()
                .map(g -> String.format("%-24s %5.1f%%", g.getKey(), g.getValue() * 100))
                .collect(Collectors.toList());
        if (rows.isEmpty()) {
            rows = List.of("(no genre data — not enough listening history yet)");
        }
        genres.setItems(FXCollections.observableArrayList(rows));

        String names = result.profile().topArtists().stream()
                .limit(8).map(Artist::name).collect(Collectors.joining(", "));
        artists.setText(names.isEmpty() ? "(none)" : names);

        String prefix = controller.isLlmConfigured() ? "" : "(offline fallback — set LLM_API_KEY for an AI summary) ";
        summary.setText(prefix + result.summary());
    }

    private void setBusy(boolean busy, String message) {
        spinner.setVisible(busy);
        viewButton.setDisable(busy || !controller.isSpotifyConfigured());
        timeRange.setDisable(busy);
        status.setText(message);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
