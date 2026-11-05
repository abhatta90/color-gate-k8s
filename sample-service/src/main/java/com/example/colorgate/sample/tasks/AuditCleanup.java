package com.example.colorgate.sample.tasks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.colorgate.ColorGated;
import com.example.colorgate.sample.Audit;

@Component
public class AuditCleanup {

    private final JdbcTemplate jdbc;
    private final Audit audit;

    public AuditCleanup(JdbcTemplate jdbc, Audit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Called from a plain executor loop in {@link LegacyStyleTasks}; a no-op while this pod is passive. */
    @ColorGated
    public void purgeOldAuditRows() {
        int deleted = jdbc.update("DELETE FROM audit_log WHERE at < now() - interval '1 day'");
        audit.record("colorgated-cleanup", null, "deleted " + deleted + " audit rows older than a day");
    }
}
