package com.example.colorgate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.event.EventListener;

/**
 * Runs the method when this pod becomes the active color: at startup if its color is already active
 * ({@code STARTUP}), otherwise when the active color is switched to it ({@code SWITCH}).
 * <p>
 * Use it instead of {@code @EventListener(ApplicationReadyEvent.class)}, {@code ApplicationRunner} or
 * {@code CommandLineRunner} for startup work that must run in one color only.
 * <p>
 * The method takes no arguments or a {@link ColorActivatedEvent}. Gated work ({@code @Scheduled},
 * {@link ColorGated}, {@link ColorGate#isActive()}) starts only after all these methods have returned, so keep them
 * short or hand long work to an executor.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EventListener(ColorActivatedEvent.class)
public @interface OnColorActivated {
}
