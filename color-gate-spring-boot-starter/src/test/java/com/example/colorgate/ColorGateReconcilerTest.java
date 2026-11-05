package com.example.colorgate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.colorgate.ColorActivatedEvent.Reason;

class ColorGateReconcilerTest {

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private volatile Consumer<Object> onEvent = event -> { };

    private final ColorGate gate = new ColorGate(true, Color.GREEN);
    private final ColorGateReconciler reconciler = new ColorGateReconciler(gate, event -> {
        onEvent.accept(event);
        events.add(event);
    });

    @AfterEach
    void stop() {
        reconciler.stop();
    }

    @Test
    void staysClosedUntilTheActiveColorIsKnown() {
        assertThat(gate.getState()).isEqualTo(GateState.UNKNOWN);
        assertThat(gate.isActive()).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void firstValueIsMineActivatesWithStartup() {
        reconciler.desire(ActiveColor.GREEN);

        awaitState(GateState.ACTIVE);
        assertThat(gate.isActive()).isTrue();
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.STARTUP));
    }

    @Test
    void otherColorFirstThenMineActivatesWithSwitch() {
        reconciler.desire(ActiveColor.BLUE);
        awaitState(GateState.PASSIVE);

        reconciler.desire(ActiveColor.GREEN);

        awaitState(GateState.ACTIVE);
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.SWITCH));
    }

    @Test
    void noneClosesTheGateAndPublishesDeactivated() {
        reconciler.desire(ActiveColor.GREEN);
        awaitState(GateState.ACTIVE);

        reconciler.desire(ActiveColor.NONE);

        awaitState(GateState.PASSIVE);
        assertThat(gate.isActive()).isFalse();
        assertThat(events).containsExactly(
                new ColorActivatedEvent(Color.GREEN, Reason.STARTUP),
                new ColorDeactivatedEvent(Color.GREEN, ActiveColor.NONE));
    }

    @Test
    void duplicateValuesChangeNothing() {
        reconciler.desire(ActiveColor.GREEN);
        awaitState(GateState.ACTIVE);

        for (int i = 0; i < 20; i++) {
            reconciler.desire(ActiveColor.GREEN);
        }

        settle();
        assertThat(events).hasSize(1);
        assertThat(gate.isActive()).isTrue();
    }

    @Test
    void handlersRunBeforeTheGateOpens() {
        List<Boolean> gateOpenDuringHandler = new CopyOnWriteArrayList<>();
        List<GateState> stateDuringHandler = new CopyOnWriteArrayList<>();
        onEvent = event -> {
            gateOpenDuringHandler.add(gate.isActive());
            stateDuringHandler.add(gate.getState());
        };

        reconciler.desire(ActiveColor.GREEN);

        awaitState(GateState.ACTIVE);
        assertThat(gateOpenDuringHandler).containsExactly(false);
        assertThat(stateDuringHandler).containsExactly(GateState.ACTIVATING);
    }

    @Test
    void valueChangedDuringActivationKeepsTheGateClosed() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        onEvent = event -> {
            if (event instanceof ColorActivatedEvent) {
                handlerStarted.countDown();
                waitFor(release);
            }
        };

        reconciler.desire(ActiveColor.GREEN);
        assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        reconciler.desire(ActiveColor.BLUE);                    // arrives while the handler is still running
        release.countDown();

        awaitState(GateState.PASSIVE);
        settle();
        assertThat(gate.isActive()).isFalse();
        assertThat(events).containsExactly(
                new ColorActivatedEvent(Color.GREEN, Reason.STARTUP),
                new ColorDeactivatedEvent(Color.GREEN, ActiveColor.BLUE));
    }

    @Test
    void burstDuringActivationIsHandledOnceWithTheLatestValue() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        onEvent = event -> {
            if (event instanceof ColorActivatedEvent) {
                handlerStarted.countDown();
                waitFor(release);
            }
        };

        reconciler.desire(ActiveColor.GREEN);
        assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 50; i++) {
            reconciler.desire(i % 2 == 0 ? ActiveColor.BLUE : ActiveColor.GREEN);   // ends with GREEN
        }
        release.countDown();

        awaitState(GateState.ACTIVE);
        settle();
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.STARTUP));
    }

    @Test
    void failingHandlerStillOpensTheGate() {
        onEvent = event -> {
            throw new IllegalStateException("handler failed");
        };

        reconciler.desire(ActiveColor.GREEN);

        awaitState(GateState.ACTIVE);
        assertThat(gate.isActive()).isTrue();
    }

    @Test
    void stopClosesTheGateForGood() {
        reconciler.desire(ActiveColor.GREEN);
        awaitState(GateState.ACTIVE);

        reconciler.stop();
        reconciler.desire(ActiveColor.GREEN);

        settle();
        assertThat(gate.getState()).isEqualTo(GateState.STOPPED);
        assertThat(gate.isActive()).isFalse();
    }

    @Test
    void noConfigMapAtStartupNeverOverridesAKnownValue() {
        reconciler.desire(ActiveColor.GREEN);
        reconciler.desireIfUnknown(ActiveColor.NONE);

        awaitState(GateState.ACTIVE);
        assertThat(reconciler.getDesired()).isEqualTo(ActiveColor.GREEN);
    }

    @Test
    void disabledGateActivatesAtStartupWithoutAValue() {
        ColorGate disabled = new ColorGate(false, null);
        ColorGateReconciler disabledReconciler = new ColorGateReconciler(disabled, events::add);
        try {
            disabledReconciler.activateWithoutGate();

            await().atMost(Duration.ofSeconds(5)).until(disabled::isActive);
            assertThat(events).containsExactly(new ColorActivatedEvent(null, Reason.STARTUP));
        } finally {
            disabledReconciler.stop();
        }
    }

    private void awaitState(GateState state) {
        await().atMost(Duration.ofSeconds(5)).until(() -> gate.getState() == state);
    }

    /** Gives the reconciler thread time to process anything still queued. */
    private static void settle() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
