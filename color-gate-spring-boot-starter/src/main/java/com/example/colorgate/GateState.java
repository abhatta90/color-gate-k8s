package com.example.colorgate;

/**
 * Where a pod is in its life. Only {@link #ACTIVE} has the gate open.
 */
public enum GateState {
    /** Started, but the active color is not known yet (Kubernetes API not read yet). Gate closed. */
    UNKNOWN,
    /** Another color, or nobody, is active. Gate closed. */
    PASSIVE,
    /** This pod's color became active; {@code @OnColorActivated} handlers are running. Gate still closed. */
    ACTIVATING,
    /** This pod's color is active and its handlers have finished. Gate open. */
    ACTIVE,
    /** The application is shutting down. Gate closed for good. */
    STOPPED
}
