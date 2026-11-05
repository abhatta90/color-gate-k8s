package com.example.colorgate.sample.tasks;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.colorgate.ColorGate;
import com.example.colorgate.sample.Audit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Background work the library cannot intercept on its own: a java.util.Timer and a plain executor loop.
 * Each needs one small change, shown below.
 */
@Component
public class LegacyStyleTasks {

    private static final Logger log = LoggerFactory.getLogger(LegacyStyleTasks.class);

    private final ColorGate colorGate;
    private final AuditCleanup cleanup;
    private final Audit audit;

    private final Timer timer = new Timer("legacy-timer", true);
    private final ScheduledExecutorService loop =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("legacy-loop").daemon().factory());

    public LegacyStyleTasks(ColorGate colorGate, AuditCleanup cleanup, Audit audit) {
        this.colorGate = colorGate;
        this.cleanup = cleanup;
        this.audit = audit;
    }

    @PostConstruct
    public void start() {
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!colorGate.isActive()) {
                    return;     // the one-line guard
                }
                safely(() -> audit.record("timer-task", null, "every 15s"));
            }
        }, 15_000, 15_000);

        // No guard here: the method it calls is @ColorGated.
        loop.scheduleWithFixedDelay(() -> safely(cleanup::purgeOldAuditRows), 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        timer.cancel();
        loop.shutdownNow();
    }

    // An uncaught exception would silently stop a Timer or a scheduled executor.
    private static void safely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Legacy task failed: {}", e.getMessage());
        }
    }
}
