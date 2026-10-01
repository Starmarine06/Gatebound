package dev.gatebound.data;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Starting classes. Each grants a one-time bonus to base stats (5 extra points in total). */
public enum GateboundClass {
    WARRIOR(Map.of(Stat.STRENGTH, 3, Stat.VITALITY, 2)),
    ROGUE(Map.of(Stat.AGILITY, 3, Stat.LUCK, 2)),
    MAGE(Map.of(Stat.INTELLECT, 4, Stat.LUCK, 1)),
    RANGER(Map.of(Stat.AGILITY, 2, Stat.STRENGTH, 1, Stat.LUCK, 2)),
    SUMMONER(Map.of(Stat.INTELLECT, 3, Stat.VITALITY, 2));

    private final Map<Stat, Integer> bonuses = new EnumMap<>(Stat.class);

    GateboundClass(Map<Stat, Integer> bonuses) {
        this.bonuses.putAll(bonuses);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int bonus(Stat stat) {
        return bonuses.getOrDefault(stat, 0);
    }

    public String nameKey() {
        return "class.gatebound." + id();
    }

    public String descriptionKey() {
        return "class.gatebound." + id() + ".desc";
    }

    public static GateboundClass byId(String id) {
        for (GateboundClass c : values()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
