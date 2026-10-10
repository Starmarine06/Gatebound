package dev.gatebound.data;

import java.util.Locale;

/** Magic attributes a Mage can awaken to. Each unlocks its own pair of skills. */
public enum Element {
    FIRE, ICE, LIGHTNING, WIND, EARTH, LIGHT, DARK;

    /** Element id. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Lang key of the element's name. */
    public String nameKey() {
        return "element.gatebound." + id();
    }

    /** Lang key of the element's description. */
    public String descriptionKey() {
        return "element.gatebound." + id() + ".desc";
    }

    /** Element by id, or null. */
    public static Element byId(String id) {
        for (Element e : values()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }
}
