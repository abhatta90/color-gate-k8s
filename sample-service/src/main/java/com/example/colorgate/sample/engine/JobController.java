package com.example.colorgate.sample.engine;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobRepository jobs;
    private final ExecutionEngine engine;

    public JobController(JobRepository jobs, ExecutionEngine engine) {
        this.jobs = jobs;
        this.engine = engine;
    }

    /**
     * Only creates SCHEDULED rows; it never starts a job itself. The calendar task in the active color starts them,
     * so it is safe to call this on either color.
     */
    @PostMapping
    public List<Long> create(@RequestParam(name = "name", defaultValue = "demo") String name,
                             @RequestParam(name = "count", defaultValue = "1") int count,
                             @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
                             @RequestParam(name = "delaySeconds", defaultValue = "0") int delaySeconds) {
        return jobs.create(name, count, durationSeconds, delaySeconds);
    }

    /**
     * Like a manual job in your system: starts right away in whichever pod receives the request,
     * even a passive one (smoke tests on green, or users once the ALB points at green).
     */
    @PostMapping("/run-now")
    public List<Long> runNow(@RequestParam(name = "name", defaultValue = "manual") String name,
                             @RequestParam(name = "count", defaultValue = "1") int count,
                             @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(engine.runNow(count == 1 ? name : name + "-" + i, durationSeconds).id());
        }
        return ids;
    }

    @GetMapping
    public List<Job> latest() {
        return jobs.latest(50);
    }
}
