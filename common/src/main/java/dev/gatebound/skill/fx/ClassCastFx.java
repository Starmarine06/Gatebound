package dev.gatebound.skill.fx;

import dev.gatebound.compat.Compat;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.skill.Skill;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.Set;

/**
 * The cast animation for the non-Mage classes, called by SkillHandler right after the mana is paid. Each class has its own
 * flavour; skills with a transformation or signature sequence of their own (level 100) skip it.
 */
public final class ClassCastFx {
    /** Warrior skills that are buffs or heals: a quiet sigil, no weapon swing. */
    private static final Set<String> WARRIOR_QUIET = Set.of("iron_skin", "bulwark", "second_wind", "berserker_rage");

    /** Summoner skills that are quick utilities: a lighter circle, no front sigil. */
    private static final Set<String> SUMMONER_QUIET = Set.of("scouts_eye", "rally", "soul_swap", "sacrifice", "soul_link");

    /** Ranger skills that are utilities rather than shots: a lighter circle. */
    private static final Set<String> RANGER_QUIET = Set.of("eagle_eye", "hunters_quarry", "evasive_roll", "hunters_trance");

    /** Static holder; not instantiable. */
    private ClassCastFx() {
    }

    /** Plays the class cast animation for a skill. */
    public static void cast(ServerPlayer p, Skill skill) {
        if (skill.minLevel() >= 100) {
            return;
        }
        GateboundClass cls = skill.owner();
        if (cls == GateboundClass.WARRIOR) {
            warrior(p, skill);
        } else if (cls == GateboundClass.RANGER) {
            RangerFx.cast(p, RANGER_QUIET.contains(skill.id()));
        } else if (cls == GateboundClass.SUMMONER) {
            SummonerFx.cast(p, SUMMONER_QUIET.contains(skill.id()));
        }
        // Rogue gets its own flavour in its batch.
    }

    /** Overhead swing plus a red sigil underfoot. */
    private static void warrior(ServerPlayer p, Skill skill) {
        ServerLevel level = p.level();
        Palette pal = Palette.of("warrior");
        boolean quiet = WARRIOR_QUIET.contains(skill.id());
        if (!quiet) {
            Compat.swing(p, InteractionHand.MAIN_HAND, true);
        }
        Circles.ground(level, "warrior", p.position(), quiet ? 2.4 : 3.0, quiet ? 12 : 14);
        Tasks.sound(level, p.position(), pal.cast(), quiet ? 0.4f : 0.7f, quiet ? 1.4f : 1.1f);
    }
}
