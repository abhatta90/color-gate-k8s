package com.example.colorgate;

import org.springframework.util.StringUtils;

/**
 * Decides this pod's own color from configuration ({@code colorgate.my-color} / {@code APP_COLOR}) and the pod's
 * namespace. Any doubt fails startup: a pod that runs with the wrong color would duplicate background work.
 */
final class MyColorResolver {

    private MyColorResolver() {
    }

    static Color resolve(String configured, String namespace) {
        Color fromConfig = Color.parse(configured);
        if (StringUtils.hasText(configured) && fromConfig == null) {
            throw new IllegalStateException("Color gate: APP_COLOR / colorgate.my-color is '" + configured
                    + "', expected BLUE or GREEN.");
        }
        Color fromNamespace = Color.fromNamespace(namespace);
        if (fromConfig != null && fromNamespace != null && fromConfig != fromNamespace) {
            throw new IllegalStateException("Color gate: APP_COLOR / colorgate.my-color says " + fromConfig
                    + " but the pod runs in namespace '" + namespace + "' (" + fromNamespace + ").");
        }
        if (fromConfig != null) {
            return fromConfig;
        }
        if (fromNamespace != null) {
            return fromNamespace;
        }
        throw new IllegalStateException("Color gate: cannot tell this pod's color. Namespace '" + namespace
                + "' is not blue/green (or *-blue/*-green) and APP_COLOR is not set. "
                + "Set APP_COLOR=BLUE or GREEN, or colorgate.enabled=false where there is no blue/green.");
    }
}
