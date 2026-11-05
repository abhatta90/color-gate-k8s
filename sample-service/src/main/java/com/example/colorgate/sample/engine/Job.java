package com.example.colorgate.sample.engine;

import java.time.OffsetDateTime;

/** A row of engine_job. Status goes SCHEDULED -> EXECUTING -> COMPLETED. */
public record Job(
        long id,
        String name,
        String status,
        int durationSeconds,
        int attempts,
        String executedBy,
        String executedByColor,
        OffsetDateTime runAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
