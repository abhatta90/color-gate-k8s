package com.example.colorgate;

/**
 * Published when this pod becomes the active color. Handle it with {@link OnColorActivated}.
 *
 * @param color  this pod's color (null when the gate is disabled)
 * @param reason how the pod became active
 */
public record ColorActivatedEvent(Color color, Reason reason) {

    public enum Reason {
        /** The first time this pod learned the active color, it was already its own color (pod start / restart). */
        STARTUP,
        /** The active color changed to this pod's color while the pod was running (release switch, rollback). */
        SWITCH
    }
}
