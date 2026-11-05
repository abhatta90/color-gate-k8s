package com.example.colorgate;

import java.util.Locale;

/**
 * The value of the {@code active-color} key in the color-gate ConfigMap: which color may run background work.
 */
public enum ActiveColor {
    BLUE, GREEN,
    /** Nobody is active: background work stops in every color (emergency stop, or before the first release). */
    NONE;

    /**
     * Strict parse of the ConfigMap value: case-insensitive, null for anything that is not BLUE, GREEN or NONE.
     */
    static ActiveColor parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    boolean is(Color color) {
        return color != null && name().equals(color.name());
    }
}
