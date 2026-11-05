package com.example.colorgate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Gauges for monitoring and alerting, read only when the registry is scraped (no timers in the application):
 * <ul>
 * <li>{@code colorgate.active}: 1 while this pod's gate is open</li>
 * <li>{@code colorgate.watching}: 1 while the informer's push connection to the Kubernetes API is up. It drops to 0
 * during an API outage while the pod keeps its state; alert if it stays 0 for a few minutes.</li>
 * <li>{@code colorgate.synced}: 1 once the informer has read the ConfigMap at least once</li>
 * </ul>
 * All tagged with {@code color} (this pod's color).
 */
class ColorGateMetrics implements MeterBinder {

    private final ColorGate gate;
    private final ConfigMapColorSource source;        // null when the gate is disabled

    ColorGateMetrics(ColorGate gate, ConfigMapColorSource source) {
        this.gate = gate;
        this.source = source;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        String color = gate.getMyColor() == null ? "none" : gate.getMyColor().name();
        Gauge.builder("colorgate.active", gate, g -> g.isActive() ? 1 : 0)
                .description("1 while this pod may run background work")
                .tag("color", color)
                .register(registry);
        if (source != null) {
            Gauge.builder("colorgate.watching", source, s -> s.isWatching() ? 1 : 0)
                    .description("1 while the push connection to the Kubernetes API is up")
                    .tag("color", color)
                    .register(registry);
            Gauge.builder("colorgate.synced", source, s -> s.isSynced() ? 1 : 0)
                    .description("1 once the active color has been read from Kubernetes")
                    .tag("color", color)
                    .register(registry);
        }
    }
}
