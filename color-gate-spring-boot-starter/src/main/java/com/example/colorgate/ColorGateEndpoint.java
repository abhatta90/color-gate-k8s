package com.example.colorgate;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/colorgate}: this pod's color and state, and what the ConfigMap says. Used by the pipeline's
 * pre-check (all new-color pods PASSIVE and synced) and post-check (all ACTIVE).
 */
@Endpoint(id = "colorgate")
public class ColorGateEndpoint {

    private final ColorGate gate;
    private final ColorGateReconciler reconciler;
    private final ConfigMapColorSource source;        // null when the gate is disabled

    ColorGateEndpoint(ColorGate gate, ColorGateReconciler reconciler, ConfigMapColorSource source) {
        this.gate = gate;
        this.reconciler = reconciler;
        this.source = source;
    }

    @ReadOperation
    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", gate.isEnabled());
        state.put("myColor", gate.getMyColor());
        state.put("state", gate.getState());
        state.put("gateOpen", gate.isActive());
        state.put("activeColor", reconciler.getDesired());
        state.put("lastTransition", gate.getLastTransition());
        if (source != null) {
            state.put("source", source.status());
        }
        return state;
    }
}
