package com.example.colorgate;

import java.util.Locale;

/**
 * A pod's own deployment color.
 */
public enum Color {
    BLUE, GREEN;

    /**
     * Lenient parse: case-insensitive, null for blank or unknown text.
     */
    static Color parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The color a namespace stands for: {@code blue} or {@code *-blue} is BLUE, {@code green} or {@code *-green}
     * is GREEN, anything else null.
     */
    static Color fromNamespace(String namespace) {
        if (namespace == null) {
            return null;
        }
        String name = namespace.trim().toLowerCase(Locale.ROOT);
        for (Color color : values()) {
            String suffix = color.name().toLowerCase(Locale.ROOT);
            if (name.equals(suffix) || name.endsWith("-" + suffix)) {
                return color;
            }
        }
        return null;
    }
}
