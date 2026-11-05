package com.musicmind.app.view;

import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Disables controls while work that uses them runs. Several tasks can queue on the worker, so each control
 * counts its locks: it is disabled while the count is above 0 and enabled again only when the last lock is
 * released. A control whose disabled state is bound (a tab behind the login gate) is left alone. JavaFX thread only.
 */
final class ControlLock {

    private final Map<BooleanProperty, Integer> counts = new IdentityHashMap<>();

    void lock(Node... nodes) {
        for (Node node : nodes) {
            lock(node.disableProperty());
        }
    }

    void unlock(Node... nodes) {
        for (Node node : nodes) {
            unlock(node.disableProperty());
        }
    }

    void lock(BooleanProperty disabled) {
        if (disabled.isBound()) {
            return;
        }
        counts.merge(disabled, 1, Integer::sum);
        disabled.set(true);
    }

    void unlock(BooleanProperty disabled) {
        Integer count = counts.get(disabled);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            counts.remove(disabled);
            if (!disabled.isBound()) {
                disabled.set(false);
            }
        } else {
            counts.put(disabled, count - 1);
        }
    }
}
