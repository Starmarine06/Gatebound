package dev.gatebound.data;

import java.util.Locale;

public enum Stat {
    STRENGTH, AGILITY, VITALITY, INTELLECT, LUCK;

    public static final int BASE_VALUE = 5;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Stat byId(String id) {
        for (Stat s : values()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }
}
