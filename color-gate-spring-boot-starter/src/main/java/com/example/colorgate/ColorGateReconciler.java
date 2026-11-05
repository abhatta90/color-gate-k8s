package com.example.colorgate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import com.example.colorgate.ColorActivatedEvent.Reason;

/**
 * Makes the pod's state match the latest active color ("desired vs actual").
 * <ul>
 * <li>Callers only hand over the latest value with {@link #desire(ActiveColor)}; that never blocks.</li>
 * <li>All state changes run on one dedicated thread, one at a time, so activation and deactivation never overlap.
 * Wake-ups that arrive while it is busy merge into a single follow-up run that reads the latest value.</li>
 * <li>Duplicate values are harmless: if the state already matches, nothing happens.</li>
 * <li>On activation the {@link OnColorActivated} handlers run first; the gate opens only afterwards, and only if the
 * value is still this pod's color.</li>
 * </ul>
 */
public class ColorGateReconciler {

    private static final Logger log = LoggerFactory.getLogger(ColorGateReconciler.class);

    private final ColorGate gate;
    private final ApplicationEventPublisher publisher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "colorgate-reconciler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean runPending = new AtomicBoolean();
    private final AtomicReference<ActiveColor> desired = new AtomicReference<>();
    private volatile boolean stopped;

    ColorGateReconciler(ColorGate gate, ApplicationEventPublisher publisher) {
        this.gate = gate;
        this.publisher = publisher;
    }

    /** Hands over the latest active color. Returns immediately; the work happens on the reconciler thread. */
    public void desire(ActiveColor value) {
        desired.set(value);
        wakeUp();
    }

    /** Hands over a value only if nothing is known yet (e.g. "no ConfigMap" found by the first read). */
    void desireIfUnknown(ActiveColor value) {
        if (desired.compareAndSet(null, value)) {
            wakeUp();
        }
    }

    /** The latest value handed over; null while nothing is known. */
    public ActiveColor getDesired() {
        return desired.get();
    }

    /** {@code colorgate.enabled=false}: activate once at startup without any ConfigMap. */
    void activateWithoutGate() {
        wakeUp();
    }

    /** Shutdown: closes the gate immediately on the calling thread, then stops the reconciler thread. */
    void stop() {
        stopped = true;
        gate.close(GateState.STOPPED);
        worker.shutdownNow();
        log.info("Color gate: {} gate CLOSED for shutdown", gate.label());
    }

    private void wakeUp() {
        if (stopped) {
            return;
        }
        if (runPending.compareAndSet(false, true)) {
            try {
                worker.execute(() -> {
                    runPending.set(false);
                    try {
                        reconcile();
                    } catch (Throwable e) {
                        log.error("Color gate: unexpected error while reconciling", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                // shutting down
            }
        }
    }

    private void reconcile() {
        if (stopped) {
            return;
        }
        ActiveColor wanted = desired.get();
        if (gate.isEnabled() && wanted == null) {
            return;                                           // nothing known yet: stay as we are
        }
        boolean mine = isMine(wanted);
        switch (gate.getState()) {
            case UNKNOWN -> {
                if (mine) {
                    activate(Reason.STARTUP);
                } else {
                    gate.close(GateState.PASSIVE);
                    log.info("Color gate: {} starts PASSIVE (active color: {})", gate.label(), wanted);
                }
            }
            case PASSIVE -> {
                if (mine) {
                    activate(Reason.SWITCH);
                }
            }
            case ACTIVE -> {
                if (!mine) {
                    deactivate(wanted);
                }
            }
            default -> {
                // ACTIVATING only exists inside activate() on this thread; STOPPED is handled above
            }
        }
    }

    private void activate(Reason reason) {
        gate.activating();
        log.info("Color gate: {} is becoming ACTIVE ({}), running @OnColorActivated handlers", gate.label(), reason);
        try {
            publisher.publishEvent(new ColorActivatedEvent(gate.getMyColor(), reason));
        } catch (RuntimeException e) {
            // Open anyway: running the handlers again could repeat work they already did.
            log.error("Color gate: an @OnColorActivated handler failed", e);
        }
        if (stopped) {
            return;
        }
        ActiveColor now = desired.get();
        if (isMine(now)) {
            gate.open();
            log.info("Color gate: {} is ACTIVE, gate OPEN", gate.label());
        } else {
            gate.close(GateState.PASSIVE);
            log.warn("Color gate: active color changed to {} while {} was activating; gate stays CLOSED",
                    now, gate.label());
            publishDeactivated(now);
        }
    }

    private void deactivate(ActiveColor wanted) {
        gate.close(GateState.PASSIVE);
        log.info("Color gate: {} is PASSIVE (active color: {}), gate CLOSED", gate.label(), wanted);
        publishDeactivated(wanted);
    }

    private void publishDeactivated(ActiveColor wanted) {
        try {
            publisher.publishEvent(new ColorDeactivatedEvent(gate.getMyColor(), wanted));
        } catch (RuntimeException e) {
            log.error("Color gate: an @OnColorDeactivated handler failed", e);
        }
    }

    private boolean isMine(ActiveColor value) {
        return !gate.isEnabled() || (value != null && value.is(gate.getMyColor()));
    }
}
