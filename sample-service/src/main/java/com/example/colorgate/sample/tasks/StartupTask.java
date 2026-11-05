package com.example.colorgate.sample.tasks;

import org.springframework.stereotype.Component;

import com.example.colorgate.ColorActivatedEvent;
import com.example.colorgate.ColorDeactivatedEvent;
import com.example.colorgate.OnColorActivated;
import com.example.colorgate.OnColorDeactivated;
import com.example.colorgate.sample.Audit;
import com.example.colorgate.sample.engine.JobRepository;

/**
 * Startup work that must run in one color only, e.g. "fetch something from the DB and submit it".
 */
@Component
public class StartupTask {

    private final JobRepository jobs;
    private final Audit audit;

    public StartupTask(JobRepository jobs, Audit audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    // Before blue/green: @EventListener(ApplicationReadyEvent.class)
    @OnColorActivated
    public void onActivated(ColorActivatedEvent event) {
        audit.record("startup-task", null, "reason=" + event.reason() + ", jobs by status: " + jobs.countByStatus());
    }

    // Optional hook: only when the color moves away while this pod keeps running (e.g. active-color=NONE).
    @OnColorDeactivated
    public void onDeactivated(ColorDeactivatedEvent event) {
        audit.record("deactivated", null, "active color is now " + event.activeColor());
    }
}
