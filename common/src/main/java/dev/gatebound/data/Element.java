package dev.gatebound.data;

import java.util.Locale;

/** Magic attributes a Mage can awaken to. Each unlocks its own pair of skills. */
public enum Element {
    FIRE, ICE, LIGHTNING, WIND, EARTH, LIGHT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String nameKey() {
        return "element.gatebound." + id();
    }

    public String descriptionKey() {
        return "element.gatebound." + id() + ".desc";
    }

    public static Element byId(String id) {
        for (Element e : values()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }
}
