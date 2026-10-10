package dev.gatebound.skill;

import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A Mage with one element gets every spell of it. A Mage with several elements still gets only nine spells in total:
 * one per unlock level (1, 5, 10, ... 100), chosen by the player from the spells their elements offer at that level.
 * A tier that cannot be a real choice (only one spell exists at it) is filled in automatically.
 */
public final class SpellPicks {
    /** Static holder; not instantiable. */
    private SpellPicks() {
    }

    /** How many spells of one tier a Mage with this many elements may take. */
    public static int allowed(int elementCount) {
        return 1;
    }

    /** Whether a Mage skill is unlocked for a Mage holding this many elements and these picks. */
    public static boolean unlocked(Skill s, int elementCount, List<String> picks) {
        return elementCount <= 1 || picks.contains(s.id());
    }

    /** Level tiers at which Mages choose spells. */
    private static TreeSet<Integer> tiers() {
        TreeSet<Integer> out = new TreeSet<>();
        for (Skill s : Skills.ALL) {
            if (s.owner() == GateboundClass.MAGE) {
                out.add(s.minLevel());
            }
        }
        return out;
    }

    /** Spells on offer at a tier for the player's elements. */
    private static List<Skill> optionsAt(PlayerData data, int tier) {
        List<Skill> out = new ArrayList<>();
        for (String elementId : data.elements) {
            for (Skill s : Skills.ALL) {
                if (s.owner() == GateboundClass.MAGE && s.element() != null && s.element().id().equals(elementId) && s.minLevel() == tier) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** How many spells of a tier the player has picked. */
    private static int pickedAt(PlayerData data, int tier) {
        int n = 0;
        for (Skill s : optionsAt(data, tier)) {
            if (data.picks.contains(s.id())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Fills in tiers that need no choice and returns the options of the first tier that still needs one (empty when none).
     * Mutates data.picks, so callers persist afterwards.
     */
    public static List<Skill> resolve(PlayerData data) {
        if (!GateboundClass.MAGE.id().equals(data.classId) || data.elements.size() <= 1) {
            return List.of();
        }
        int allowed = allowed(data.elements.size());
        for (int tier : tiers()) {
            if (tier > data.level) {
                break;
            }
            int remaining = allowed - pickedAt(data, tier);
            if (remaining <= 0) {
                continue;
            }
            List<Skill> open = new ArrayList<>();
            for (Skill s : optionsAt(data, tier)) {
                if (!data.picks.contains(s.id())) {
                    open.add(s);
                }
            }
            if (open.size() <= remaining) {
                for (Skill s : open) {
                    data.picks.add(s.id());
                }
                continue;
            }
            return open;
        }
        return List.of();
    }

    /** Takes a pick if it is one of the options currently offered. */
    public static boolean choose(PlayerData data, String skillId) {
        for (Skill s : resolve(data)) {
            if (s.id().equals(skillId)) {
                data.picks.add(skillId);
                return true;
            }
        }
        return false;
    }
}
