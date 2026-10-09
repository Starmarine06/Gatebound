package dev.gatebound.skill.fx;

import dev.gatebound.compat.Compat;
import dev.gatebound.data.Element;
import dev.gatebound.skill.mage.MageCore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/** The cast animation every Mage spell shares, plus the bigger one for the level 100 forms. */
public final class CastFx {
    private CastFx() {
    }

    /** Arm swing everyone can see, a turning magic circle at the caster's feet, and sparks winding up the body. */
    public static void cast(ServerPlayer p, Element element) {
        ServerLevel level = p.level();
        Palette pal = Palette.of(element);
        Compat.swing(p, InteractionHand.MAIN_HAND, true);
        Circles.ground(level, element, p.position(), 3.2, 16);   // under the feet
        Circles.castCircle(p, element, 3.0, 12);                 // upright in front, the spell is cast through it
        Fx.orbit(p, pal, 12);
        MageCore.sound(level, p.position(), pal.cast(), 0.7f, 1.3f);
    }

    /** A transformation: pillar, big circle, shockwave and a burst of sparks. The form's own aura carries on after it. */
    public static void transform(ServerPlayer p, Element element) {
        ServerLevel level = p.level();
        Palette pal = Palette.of(element);
        Vec3 at = p.position();
        Fx.pillar(level, pal, at, 7, 1.4, 14);
        Circles.ground(level, element, at, 6.0, 44);
        Circles.castCircle(p, element, 4.2, 20);
        Fx.shockwave(level, pal, at, 7, 10);
        Fx.spiral(level, pal.main(), at, 1.2, 3.2, 3, 16);
        Fx.sphere(level, pal.accent(), at.add(0, 1, 0), 30, 0.35);
        MageCore.flash(level, at.x, at.y + 1, at.z);
        MageCore.sound(level, at, pal.hit(), 1.2f, 0.7f);
    }
}
