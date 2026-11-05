package com.example.colorgate.sample.tasks;

import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.colorgate.RunInAllColors;
import com.example.colorgate.sample.Audit;
import com.example.colorgate.sample.engine.JobRepository;

/**
 * Plain {@code @Scheduled} methods. The library gates them automatically; none of them needed a code change
 * except the cache refresh, which opts out with {@link RunInAllColors}.
 */
@Component
public class DemoTasks {

    private final JobRepository jobs;
    private final Audit audit;

    private volatile Map<String, Long> statusCache = Map.of();

    public DemoTasks(JobRepository jobs, Audit audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    /** Active color only. */
    @Scheduled(fixedRate = 20_000)
    public void everyTwentySeconds() {
        audit.record("every-20s", null, "jobs by status: " + jobs.countByStatus());
    }

    /** Active color only. A fire time that falls inside the switch window is missed (accepted trade-off). */
    @Scheduled(cron = "0 * * * * *")
    public void cronEveryMinute() {
        audit.record("cron-every-minute", null, "fired");
    }

    /** Pod-local work, so it runs in every color. */
    @RunInAllColors
    @Scheduled(fixedRate = 30_000)
    public void refreshLocalCache() {
        statusCache = jobs.countByStatus();
        audit.record("cache-refresh", null, "runs in every color, cached " + statusCache);
    }
}
