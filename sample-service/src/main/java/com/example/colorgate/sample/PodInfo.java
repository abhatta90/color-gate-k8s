package com.example.colorgate.sample;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.colorgate.ColorGate;

/**
 * Who this pod is: its color and a readable instance id, stamped on jobs (engine_job.executed_by_color,
 * executed_by) and on audit rows.
 */
@Component
public class PodInfo {

    private final String color;
    private final String instance;

    public PodInfo(ColorGate gate, @Value("${server.port:8080}") int port) {
        this.color = gate.getMyColor() == null ? "NONE" : gate.getMyColor().name();
        this.instance = color + ":" + port + " pid " + ProcessHandle.current().pid();
    }

    /** BLUE, GREEN, or NONE when the gate is disabled. */
    public String color() {
        return color;
    }

    /** e.g. "GREEN:8082 pid 7118". */
    public String instance() {
        return instance;
    }
}
