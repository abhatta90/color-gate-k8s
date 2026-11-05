package com.example.colorgate;

import java.time.Instant;

/**
 * Answers one question for background work: may this pod start work right now?
 * <p>
 * {@link #isActive()} only reads a volatile field: it never blocks, waits or does I/O. Use it as the first line of a
 * {@code TimerTask}, executor loop or any background code the library cannot intercept. The state is changed only by
 * {@link ColorGateReconciler} (and on shutdown).
 */
public class ColorGate {

    private final boolean enabled;
    private final Color myColor;

    private volatile GateState state = GateState.UNKNOWN;
    private volatile boolean open;
    private volatile Instant lastTransition = Instant.now();

    ColorGate(boolean enabled, Color myColor) {
        this.enabled = enabled;
        this.myColor = myColor;
    }

    /** True when this pod's color is the active one and its {@link OnColorActivated} handlers have finished. */
    public boolean isActive() {
        return open;
    }

    public GateState getState() {
        return state;
    }

    /** False when {@code colorgate.enabled=false}: the pod is then always active after startup. */
    public boolean isEnabled() {
        return enabled;
    }

    /** This pod's color; null when the gate is disabled. */
    public Color getMyColor() {
        return myColor;
    }

    public Instant getLastTransition() {
        return lastTransition;
    }

    void open() {
        state = GateState.ACTIVE;
        lastTransition = Instant.now();
        open = true;
    }

    void close(GateState newState) {
        open = false;
        state = newState;
        lastTransition = Instant.now();
    }

    void activating() {
        state = GateState.ACTIVATING;
        lastTransition = Instant.now();
    }

    String label() {
        return enabled ? myColor.name() : "(gate disabled)";
    }
}
