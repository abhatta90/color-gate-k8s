package com.example.colorgate.sample.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.colorgate.sample.PodInfo;

/**
 * All SQL for engine_job. Every statement that makes a job EXECUTING is here and stamps this pod's
 * executed_by and executed_by_color, so no code path can forget the color.
 */
@Repository
public class JobRepository {

    private static final RowMapper<Job> JOB = new DataClassRowMapper<>(Job.class);

    private final JdbcTemplate jdbc;
    private final PodInfo pod;

    public JobRepository(JdbcTemplate jdbc, PodInfo pod) {
        this.jdbc = jdbc;
        this.pod = pod;
    }

    public List<Long> create(String name, int count, int durationSeconds, int delaySeconds) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(jdbc.queryForObject("""
                    INSERT INTO engine_job (name, status, duration_seconds, run_at)
                    VALUES (?, 'SCHEDULED', ?, now() + ? * interval '1 second')
                    RETURNING id
                    """, Long.class, numbered(name, count, i), durationSeconds, delaySeconds));
        }
        return ids;
    }

    /** Manual job: inserted straight as EXECUTING by the pod that received the request. */
    public Job insertExecuting(String name, int durationSeconds) {
        return jdbc.queryForObject("""
                INSERT INTO engine_job (name, status, duration_seconds, attempts, executed_by, executed_by_color, started_at)
                VALUES (?, 'EXECUTING', ?, 1, ?, ?, now())
                RETURNING *
                """, JOB, name, durationSeconds, pod.instance(), pod.color());
    }

    /** Claims due jobs (no other pod can claim the same rows) and marks them EXECUTING. */
    public List<Job> claimDue(int limit) {
        return jdbc.query("""
                UPDATE engine_job
                   SET status = 'EXECUTING', attempts = attempts + 1, executed_by = ?, executed_by_color = ?,
                       started_at = now()
                 WHERE id IN (SELECT id FROM engine_job
                               WHERE status = 'SCHEDULED' AND run_at <= now()
                               ORDER BY run_at
                               LIMIT ?
                               FOR UPDATE SKIP LOCKED)
                RETURNING *
                """, JOB, pod.instance(), pod.color(), limit);
    }

    public List<Job> findExecuting() {
        return jdbc.query("SELECT * FROM engine_job WHERE status = 'EXECUTING' ORDER BY id", JOB);
    }

    /** EXECUTING jobs started by another color. NULL counts as another color: rows from code that predates the column. */
    public List<Job> findExecutingNotRunBy(String color) {
        return jdbc.query("""
                SELECT * FROM engine_job
                 WHERE status = 'EXECUTING' AND executed_by_color IS DISTINCT FROM ?
                 ORDER BY id
                """, JOB, color);
    }

    public Job markResubmitted(long id) {
        return jdbc.queryForObject("""
                UPDATE engine_job
                   SET attempts = attempts + 1, executed_by = ?, executed_by_color = ?, started_at = now()
                 WHERE id = ?
                RETURNING *
                """, JOB, pod.instance(), pod.color(), id);
    }

    public void markCompleted(long id) {
        jdbc.update("UPDATE engine_job SET status = 'COMPLETED', finished_at = now() WHERE id = ?", id);
    }

    public List<Job> latest(int limit) {
        return jdbc.query("SELECT * FROM engine_job ORDER BY id DESC LIMIT ?", JOB, limit);
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) FROM engine_job GROUP BY status ORDER BY status",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }

    private static String numbered(String name, int count, int i) {
        return count == 1 ? name : name + "-" + i;
    }
}
