package com.example.colorgate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a {@code @Scheduled} method, or every {@code @Scheduled} method of a class, out of the color gate
 * so it runs in every color. For work that is local to the pod, such as refreshing an in-memory cache.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RunInAllColors {
}
