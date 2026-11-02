package com.musicmind.app;

import com.musicmind.app.view.DiscoveryView;
import com.musicmind.app.view.GuiContext;
import com.musicmind.app.view.HistoryView;
import com.musicmind.app.view.PlaylistView;
import com.musicmind.app.view.PreferencesView;
import com.musicmind.app.view.ProfileView;
import com.musicmind.app.view.SearchView;
import com.musicmind.app.view.StatusLine;
import com.musicmind.app.view.TabView;
import com.musicmind.app.view.TrendsView;
import com.musicmind.app.view.ViewFormat;
import com.musicmind.config.Config;
import com.musicmind.controller.AppController;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.List;

/**
 * JavaFX GUI entry point. The views are built over the same {@link AppController} the CLI uses (MVC): a
 * view shows what the controller returns and sends the user's actions back, and holds no business logic.
 * Every controller call runs on one background worker ({@link GuiContext}); opening the window never calls
 * Spotify or opens a browser, which only the login button does.
 */
public class GuiApp extends Application {

    private static final double WIDTH = 1040;
    private static final double HEIGHT = 760;
    private static final double TITLE_SIZE = 26;
    private static final String BANNER_STYLE = "-fx-background-color: #fff3cd; -fx-text-fill: #7a5a00; -fx-padding: 6 12 6 12;";

    private GuiContext ctx;

    /** Runs before the window exists, off the JavaFX thread: building the controller may connect to the database. */
    @Override
    public void init() {
        Config config = Config.load();
        ctx = new GuiContext(AppController.create(), config);
    }

    @Override
    public void start(Stage stage) {
        ViewFormat format = ctx.format();
        List<TabView> views = List.of(new ProfileView(ctx), new TrendsView(ctx), new SearchView(ctx),
                new DiscoveryView(ctx), new PlaylistView(ctx), new PreferencesView(ctx), new HistoryView(ctx));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (TabView view : views) {
            Tab tab = new Tab(view.title(), view.node());
            tabs.getTabs().add(tab);
        }
        ctx.onLogin(user -> views.forEach(view -> view.onLogin(user)));

        BorderPane root = new BorderPane();
        root.setTop(new VBox(header(format), banners(format)));
        root.setCenter(tabs);
        root.setBottom(footer());

        stage.setScene(new Scene(root, WIDTH, HEIGHT));
        stage.setTitle(format.w("Title"));
        stage.setOnHidden(e -> ctx.shutdown());
        stage.show();
        ctx.checkDatabase();
    }

    /** Title, who is logged in, and the login button. */
    private VBox header(ViewFormat format) {
        Label title = new Label(format.w("Title"));
        title.setFont(Font.font(TITLE_SIZE));
        Label subtitle = new Label(format.w("Subtitle"));
        VBox names = new VBox(title, subtitle);

        Label who = new Label(format.w("NotLoggedIn"));
        Button login = new Button(format.w("LoginButton"));
        login.setDisable(!ctx.spotifyConfigured());
        StatusLine status = new StatusLine();
        login.setOnAction(e -> ctx.login(status, login));
        ctx.userProperty().addListener((observable, was, user) -> {
            who.setText(user == null ? format.w("NotLoggedIn") : format.w("LoggedInAs", user.id()));
            login.setDisable(user != null || !ctx.spotifyConfigured());
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, names, spacer, who, login);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10, 12, 6, 12));
        return new VBox(top, status);
    }

    /** The notes that say what is missing: Spotify settings, the LLM key, the database. */
    private VBox banners(ViewFormat format) {
        VBox box = new VBox();
        if (!ctx.spotifyConfigured()) {
            box.getChildren().add(banner(format.w("BannerSpotify")));
        }
        if (!ctx.llmConfigured()) {
            box.getChildren().add(banner(format.w("BannerLlm")));
        }
        Label database = banner(format.w("BannerDatabase"));
        database.visibleProperty().bind(ctx.databaseDownProperty());
        database.managedProperty().bind(database.visibleProperty());
        box.getChildren().add(database);
        return box;
    }

    private static Label banner(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle(BANNER_STYLE);
        return label;
    }

    /** The credit Spotify's and MusicBrainz's terms ask for. */
    private VBox footer() {
        Label spotify = new Label(ctx.config().spotifyAttribution());
        Label genres = new Label(ctx.config().genreAttribution());
        VBox box = new VBox(2, spotify, genres);
        box.setPadding(new Insets(6, 12, 6, 12));
        box.setStyle("-fx-text-fill: #666666;");
        spotify.setStyle("-fx-text-fill: #666666;");
        genres.setStyle("-fx-text-fill: #666666;");
        return box;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
