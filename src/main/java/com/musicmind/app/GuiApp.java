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
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX GUI entry point. The views are built over the same {@link AppController} the CLI uses (MVC): a
 * view shows what the controller returns and sends the user's actions back, and holds no business logic.
 * Every controller call runs on one background worker ({@link GuiContext}); opening the window never calls
 * Spotify or opens a browser, which only the login button does.
 */
public class GuiApp extends Application {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 780;
    private static final double MIN_WIDTH = 1100;
    private static final double MIN_HEIGHT = 720;
    private static final String STYLESHEET = "musicmind.css";
    private static final String MENU_ITEM = "mm-menu-item";
    private static final String MENU_ITEM_SELECTED = "mm-menu-item-selected";

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
        ctx.setHostServices(getHostServices());
        List<TabView> views = List.of(new ProfileView(ctx), new TrendsView(ctx), new SearchView(ctx),
                new DiscoveryView(ctx), new PlaylistView(ctx), new PreferencesView(ctx), new HistoryView(ctx));
        ctx.onLogin(user -> views.forEach(view -> view.onLogin(user)));

        ScrollPane content = new ScrollPane();
        content.getStyleClass().add("mm-content");
        content.setFitToWidth(true);
        content.setFitToHeight(true);

        BorderPane root = new BorderPane();
        root.setTop(new VBox(header(format), banners(format)));
        root.setLeft(menu(views, content));
        root.setCenter(content);
        root.setBottom(footer());

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        URL stylesheet = GuiApp.class.getResource(STYLESHEET);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setTitle(format.w("Title"));
        stage.setOnHidden(e -> ctx.shutdown());
        stage.show();
        ctx.checkDatabase();
    }

    /** The left menu: one button per screen, the chosen one marked; the chosen screen fills {@code content}. */
    private VBox menu(List<TabView> views, ScrollPane content) {
        VBox menu = new VBox();
        menu.getStyleClass().add("mm-menu");
        List<Button> buttons = new ArrayList<>();
        for (TabView view : views) {
            Button item = new Button(view.title());
            item.getStyleClass().add(MENU_ITEM);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setOnAction(e -> {
                buttons.forEach(b -> b.getStyleClass().remove(MENU_ITEM_SELECTED));
                item.getStyleClass().add(MENU_ITEM_SELECTED);
                content.setContent(view.node());
                view.onShown();
            });
            buttons.add(item);
            menu.getChildren().add(item);
        }
        buttons.get(0).fire();
        return menu;
    }

    /** The MusicMind name, who is logged in (by display name), and the login button. */
    private VBox header(ViewFormat format) {
        Label brand = new Label(format.w("BrandFirst"));
        brand.getStyleClass().add("mm-brand");
        Label brandAccent = new Label(format.w("BrandSecond"));
        brandAccent.getStyleClass().add("mm-brand-accent");
        HBox names = new HBox(brand, brandAccent);

        Label who = new Label(format.w("NotLoggedIn"));
        who.getStyleClass().add("mm-user");
        Button login = new Button(format.w("LoginButton"));
        login.getStyleClass().add("mm-primary");
        login.setDisable(!ctx.spotifyConfigured());
        StatusLine status = new StatusLine();
        status.setPadding(new Insets(0, 18, 4, 18));
        login.setOnAction(e -> ctx.login(status, login));
        ctx.userProperty().addListener((observable, was, user) -> {
            login.setDisable(user != null || !ctx.spotifyConfigured());
            login.setVisible(user == null);
            login.setManaged(user == null);
            if (user == null) {
                who.setText(format.w("NotLoggedIn"));
                return;
            }
            who.setText(format.w("LoggedInAs", user.id()));
            showDisplayName(who, user.id(), format);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(12, names, spacer, who, login);
        top.getStyleClass().add("mm-header");
        top.setAlignment(Pos.CENTER_LEFT);
        return new VBox(top, status);
    }

    /** Replaces the user id in the header with the Spotify display name; keeps the id if the name cannot be read. */
    private void showDisplayName(Label who, String userId, ViewFormat format) {
        ctx.run(new StatusLine(), "ActionLogin", format.w("Working"), () -> {
            try {
                return ctx.controller().currentUserName();
            } catch (RuntimeException e) {
                return userId;
            }
        }, name -> {
            if (ctx.userProperty().get() != null && userId.equals(ctx.userProperty().get().id())) {
                who.setText(format.w("LoggedInAs", name == null || name.isBlank() ? userId : name));
            }
        });
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
        label.getStyleClass().add("mm-banner");
        return label;
    }

    /** The credit Spotify's and MusicBrainz's terms ask for. */
    private VBox footer() {
        VBox box = new VBox(2, new Label(ctx.config().spotifyAttribution()), new Label(ctx.config().genreAttribution()));
        box.getStyleClass().add("mm-footer");
        return box;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
