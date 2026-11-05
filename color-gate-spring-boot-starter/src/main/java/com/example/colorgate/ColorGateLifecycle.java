package com.example.colorgate;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Starts the color gate once the application is ready, and closes it first thing on shutdown.
 * <p>
 * {@link ContextClosedEvent} is published before Spring stops schedulers and destroys beans, so the gate is closed
 * before anything else shuts down: from that moment no new background work starts in this pod.
 */
class ColorGateLifecycle {

    private final ColorGateReconciler reconciler;
    private final ConfigMapColorSource source;            // null when the gate is disabled
    private final ApplicationContext applicationContext;
    private final AtomicBoolean started = new AtomicBoolean();

    ColorGateLifecycle(ColorGateReconciler reconciler, ConfigMapColorSource source,
                       ApplicationContext applicationContext) {
        this.reconciler = reconciler;
        this.source = source;
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (source == null) {
            reconciler.activateWithoutGate();
        } else {
            source.start();
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop(ContextClosedEvent event) {
        if (event.getApplicationContext() != applicationContext) {
            return;                                           // a child context (e.g. management) is closing
        }
        reconciler.stop();
        if (source != null) {
            source.stop();
        }
    }
}
