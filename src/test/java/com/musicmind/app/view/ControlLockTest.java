package com.musicmind.app.view;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlLockTest {

    private final ControlLock locks = new ControlLock();

    @Test
    void aControlIsEnabledAgainOnlyWhenTheLastLockIsReleased() {
        BooleanProperty disabled = new SimpleBooleanProperty(false);

        locks.lock(disabled);
        locks.lock(disabled);
        assertTrue(disabled.get());

        locks.unlock(disabled);
        assertTrue(disabled.get(), "a second task still holds it");

        locks.unlock(disabled);
        assertFalse(disabled.get());
    }

    @Test
    void aBoundControlIsSkippedWithoutThrowing() {
        BooleanProperty disabled = new SimpleBooleanProperty();
        BooleanProperty source = new SimpleBooleanProperty(true);
        disabled.bind(source);

        assertDoesNotThrow(() -> locks.lock(disabled));
        assertDoesNotThrow(() -> locks.unlock(disabled));
        assertTrue(disabled.get(), "the binding still decides");
    }

    @Test
    void unlockingAControlThatWasNeverLockedChangesNothing() {
        BooleanProperty disabled = new SimpleBooleanProperty(true);
        locks.unlock(disabled);
        assertTrue(disabled.get());
    }
}
