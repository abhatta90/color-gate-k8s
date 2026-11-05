package com.example.colorgate.sample.engine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every second, hands jobs whose run_at has come to the engine.
 * <p>
 * It is {@code @Scheduled}, so the library gates it automatically: no code change, and it does nothing
 * while this pod is passive. Due jobs simply wait in the table until the active color picks them up.
 */
@Component
public class CalendarTask {

    private final JobRepository jobs;
    private final ExecutionEngine engine;

    public CalendarTask(JobRepository jobs, ExecutionEngine engine) {
        this.jobs = jobs;
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 1000)
    public void triggerDueJobs() {
        for (Job job : jobs.claimDue(100)) {
            engine.submit(job);
        }
    }
}
