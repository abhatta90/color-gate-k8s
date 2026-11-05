package com.example.colorgate;

/**
 * Published when this pod stops being the active color <b>while it keeps running</b>: the active color was set to
 * another color or to NONE. Handle it with {@link OnColorDeactivated}.
 * <p>
 * Not published on shutdown: a pod that stops is not a color change (use {@code @PreDestroy} for shutdown work). In
 * the normal release flow the old color is stopped before the switch, so its pods never see this event.
 *
 * @param color       this pod's color
 * @param activeColor what the ConfigMap says now
 */
public record ColorDeactivatedEvent(Color color, ActiveColor activeColor) {
}
