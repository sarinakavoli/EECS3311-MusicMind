package com.musicmind.app.view;

import com.musicmind.agent.NegotiationHandler;
import com.musicmind.config.Config;
import com.musicmind.controller.AppController;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.User;
import com.musicmind.music.AuthExpiredException;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What every view shares: the {@link AppController}, the wording, who is logged in, and the one background
 * worker. {@code AppController} keeps mutable {@code last*()} state, so every call to it from the GUI runs on
 * this single worker thread, in order; a task reads the {@code last*()} values it needs before it returns
 * its result, and only then does the result reach the JavaFX thread. The JavaFX thread never waits for the
 * network or the database.
 */
public final class GuiContext {

    private final AppController controller;
    private final ViewFormat format;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "musicmind-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectProperty<User> user = new SimpleObjectProperty<>();
    private final BooleanProperty databaseDown = new SimpleBooleanProperty(false);
    private final List<Consumer<User>> loginListeners = new ArrayList<>();

    public GuiContext(AppController controller, Config config) {
        this.controller = controller;
        this.format = new ViewFormat(config);
    }

    /** The controller. Call it only inside a task given to {@link #run}. */
    public AppController controller() {
        return controller;
    }

    public ViewFormat format() {
        return format;
    }

    public Config config() {
        return format.config();
    }

    /** The logged-in user; null before login and after the Spotify login expires. */
    public ObjectProperty<User> userProperty() {
        return user;
    }

    /** True while the database cannot be reached (drives the banner). */
    public BooleanProperty databaseDownProperty() {
        return databaseDown;
    }

    /** Called on the JavaFX thread each time a login succeeds. */
    public void onLogin(Consumer<User> listener) {
        loginListeners.add(listener);
    }

    /** Whether an LLM key is configured; the controller answers this without any I/O. */
    public boolean llmConfigured() {
        return controller.isLlmConfigured();
    }

    /** Whether Spotify is configured; the controller answers this without any I/O. */
    public boolean spotifyConfigured() {
        return controller.isSpotifyConfigured();
    }

    /**
     * Runs {@code work} on the worker, then {@code onSuccess} on the JavaFX thread with its result. While it
     * runs the {@code locked} controls are disabled and {@code status} shows {@code busyText}; if it fails the
     * status shows a friendly message for {@code action} (an expired Spotify login also logs the user out so
     * the login button comes back). Must be called on the JavaFX thread.
     */
    public <T> void run(StatusLine status, String action, String busyText, Supplier<T> work,
                        Consumer<T> onSuccess, Node... locked) {
        status.busy(busyText);
        for (Node node : locked) {
            node.setDisable(true);
        }
        worker.execute(() -> {
            T result = null;
            Throwable failure = null;
            try {
                result = work.get();
            } catch (Throwable t) {
                failure = t;
            }
            boolean down = databaseIsDown();
            T finalResult = result;
            Throwable finalFailure = failure;
            Platform.runLater(() -> finish(status, action, locked, down, finalResult, finalFailure, onSuccess));
        });
    }

    private <T> void finish(StatusLine status, String action, Node[] locked, boolean down, T result,
                            Throwable failure, Consumer<T> onSuccess) {
        for (Node node : locked) {
            node.setDisable(false);
        }
        databaseDown.set(down);
        if (failure != null) {
            status.error(format.failure(action, failure));
            if (failure instanceof AuthExpiredException) {
                user.set(null);
            }
            return;
        }
        status.info("");
        onSuccess.accept(result);
    }

    /** Runs on the worker: the database is down when the last operation warned, or when a recheck cannot connect. */
    private boolean databaseIsDown() {
        try {
            if (controller.memory().warning() != null) {
                return true;
            }
            return databaseDown.get() && !controller.memory().isAvailable();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Checks the database once, at start-up, so the banner shows before anything is saved. */
    public void checkDatabase() {
        worker.execute(() -> {
            boolean down;
            try {
                down = !controller.memory().isAvailable();
            } catch (RuntimeException e) {
                down = true;
            }
            boolean finalDown = down;
            Platform.runLater(() -> databaseDown.set(finalDown));
        });
    }

    /**
     * Logs in (opens the browser only if there is no saved Spotify login) on the worker. Only the login
     * button calls this; opening the GUI never does.
     */
    public void login(StatusLine status, Node... locked) {
        status.busy(format.w("LoggingIn"));
        for (Node node : locked) {
            node.setDisable(true);
        }
        worker.execute(() -> {
            User loggedIn = null;
            Throwable failure = null;
            try {
                if (controller.willOpenBrowser()) {
                    Platform.runLater(() -> status.busy(format.w("BrowserLogin")));
                }
                loggedIn = controller.login();
                controller.setNegotiationHandler(negotiationHandler());
            } catch (Throwable t) {
                failure = t;
            }
            User finalUser = loggedIn;
            Throwable finalFailure = failure;
            Platform.runLater(() -> {
                for (Node node : locked) {
                    node.setDisable(false);
                }
                if (finalFailure != null) {
                    status.error(format.failure("log in to Spotify", finalFailure));
                    return;
                }
                status.info("");
                user.set(finalUser);
                loginListeners.forEach(l -> l.accept(finalUser));
            });
        });
    }

    /**
     * F11: asked on the worker thread in the middle of a create or modify. The dialog is shown on the JavaFX
     * thread and the worker waits for the answer; closing the dialog or cancelling answers "no option".
     */
    private NegotiationHandler negotiationHandler() {
        return (FeasibilityReport report, List<String> options) -> {
            CompletableFuture<Optional<Relaxation>> answer = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    answer.complete(NegotiationDialog.ask(format, report, options));
                } catch (RuntimeException e) {
                    answer.complete(Optional.empty());
                }
            });
            return answer.join();
        };
    }

    /** Stops the worker when the window closes. */
    public void shutdown() {
        worker.shutdownNow();
    }
}
