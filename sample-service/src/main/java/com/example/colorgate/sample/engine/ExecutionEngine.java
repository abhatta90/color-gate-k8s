package com.example.colorgate.sample.engine;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.colorgate.ColorActivatedEvent;
import com.example.colorgate.ColorActivatedEvent.Reason;
import com.example.colorgate.OnColorActivated;
import com.example.colorgate.sample.Audit;
import com.example.colorgate.sample.PodInfo;

import jakarta.annotation.PreDestroy;

/**
 * Stand-in for your execution engine: each job "runs" on its own virtual thread for duration_seconds,
 * then is marked COMPLETED.
 */
@Component
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JobRepository jobs;
    private final Audit audit;
    private final PodInfo pod;

    public ExecutionEngine(JobRepository jobs, Audit audit, PodInfo pod) {
        this.jobs = jobs;
        this.audit = audit;
        this.pod = pod;
    }

    public void submit(Job job) {
        executor.submit(() -> run(job));
    }

    /** Manual job: starts right away in this pod, active or passive (e.g. a smoke test on green before the switch). */
    public Job runNow(String name, int durationSeconds) {
        Job job = jobs.insertExecuting(name, durationSeconds);
        submit(job);
        return job;
    }

    private void run(Job job) {
        audit.record("job-started", job.id(),
                job.name() + " (" + job.durationSeconds() + "s, attempt " + job.attempts() + ")");
        try {
            Thread.sleep(Duration.ofSeconds(job.durationSeconds()));
        } catch (InterruptedException e) {
            // The pod is shutting down. Like a crash, the job stays EXECUTING and the next active pod resubmits it.
            log.warn("Job {} interrupted by shutdown, left EXECUTING", job.id());
            return;
        }
        jobs.markCompleted(job.id());
        audit.record("job-finished", job.id(), job.name());
    }

    /**
     * Resubmits jobs left EXECUTING by a pod that went away.
     * <p>
     * Before blue/green this was {@code @EventListener(ApplicationReadyEvent.class)}. Now it runs when this pod
     * becomes active:
     * <ul>
     * <li>SWITCH: the pipeline stopped the old color and switched to ours. Only the old color's jobs are orphans;
     * jobs this color started while passive (manual jobs, smoke tests) are still running.</li>
     * <li>STARTUP: this pod (re)started while its color was already active. Every EXECUTING job is treated as
     * orphaned, exactly as before blue/green.</li>
     * </ul>
     */
    @OnColorActivated
    public void recoverExecutingJobs(ColorActivatedEvent event) {
        List<Job> orphans = event.reason() == Reason.SWITCH
                ? jobs.findExecutingNotRunBy(pod.color())
                : jobs.findExecuting();
        log.info("Recovery ({}): {} EXECUTING job(s) to resubmit", event.reason(), orphans.size());
        for (Job orphan : orphans) {
            Job job = jobs.markResubmitted(orphan.id());
            audit.record("job-recovered", job.id(),
                    job.name() + " (" + event.reason() + ", was run by " + orphan.executedBy() + ")");
            submit(job);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
