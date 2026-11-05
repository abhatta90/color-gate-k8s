package com.example.colorgate.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records every piece of background work in audit_log (and the console) with the color that did it,
 * so scripts/status.sh can show who ran what and spot duplicates or gaps.
 */
@Component
public class Audit {

    private static final Logger log = LoggerFactory.getLogger(Audit.class);

    private final JdbcTemplate jdbc;
    private final PodInfo pod;

    public Audit(JdbcTemplate jdbc, PodInfo pod) {
        this.jdbc = jdbc;
        this.pod = pod;
    }

    public void record(String task, Long jobId, String detail) {
        log.info("[{}] {}{} - {}", pod.color(), task, jobId == null ? "" : " job=" + jobId, detail);
        jdbc.update("INSERT INTO audit_log (color, instance, task, job_id, detail) VALUES (?, ?, ?, ?, ?)",
                pod.color(), pod.instance(), task, jobId, detail);
    }
}
