package com.example.colorgate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the method only while this pod is the active color; otherwise the call is skipped and returns null.
 * <p>
 * For void methods called from your own timers, executors or loops. {@code @Scheduled} methods are gated
 * without it. Like any Spring proxy annotation it does not apply to calls from inside the same class.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ColorGated {
}
