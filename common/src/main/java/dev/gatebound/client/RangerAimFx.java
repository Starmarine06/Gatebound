package dev.gatebound.client;

import dev.gatebound.compat.ClientCompat;
import dev.gatebound.data.ClientPlayerData;
import dev.gatebound.data.GateboundClass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * What a drawing Ranger sees and feels (client only): the world drains to grey, the camera zooms toward the crosshair,
 * and the usual slow-walk of using an item is undone so the Ranger keeps moving at normal speed.
 */
public final class RangerAimFx {
    private static final Identifier FOCUS = Identifier.fromNamespaceAndPath("gatebound", "focus");
    /** Fraction of the player's field of view kept while aiming. */
    private static final double ZOOM = 0.55;

    private static Integer savedFov;

    /** Static holder; not instantiable. */
    private RangerAimFx() {
    }

    /** True if the player is aiming a bow. */
    private static boolean aiming(LocalPlayer p) {
        return GateboundClass.RANGER.id().equals(ClientPlayerData.classId) && p.isUsingItem()
                && (p.getUseItem().getItem() instanceof BowItem || p.getUseItem().getItem() instanceof CrossbowItem);
    }

    /** Per-tick aim handling: zoom, walking pace and restore. */
    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            restoreFov(mc, true);
            return;
        }
        boolean aim = aiming(p);
        grey(p, aim);
        zoom(mc, aim);
        if (aim) {
            keepWalking(p);
        }
    }

    /** Adds or removes the greyscale post effect without disturbing any other active effects. */
    private static void grey(LocalPlayer p, boolean on) {
        List<Identifier> current = ClientCompat.postEffects(p);
        boolean has = current.contains(FOCUS);
        if (on == has) {
            return;
        }
        List<Identifier> next = new ArrayList<>(current);
        if (on) {
            next.add(FOCUS);
        } else {
            next.remove(FOCUS);
        }
        ClientCompat.setPostEffects(p, next);
    }

    /** Zooms the FOV in while aiming and back out afterwards. */
    private static void zoom(Minecraft mc, boolean aim) {
        var option = mc.options.fov();
        if (aim) {
            if (savedFov == null) {
                savedFov = option.get();
            }
            int target = Math.max(30, (int) Math.round(savedFov * ZOOM));
            int cur = option.get();
            if (cur > target) {
                option.set(Math.max(target, cur - 5));
            }
        } else {
            restoreFov(mc, false);
        }
    }

    /** Returns the FOV to its saved value, instantly or gradually. */
    private static void restoreFov(Minecraft mc, boolean immediately) {
        if (savedFov == null) {
            return;
        }
        var option = mc.options.fov();
        int cur = option.get();
        if (immediately || cur + 6 >= savedFov) {
            option.set(savedFov);
            savedFov = null;
        } else {
            option.set(cur + 6);
        }
    }

    /** Vanilla scales movement to a fifth while an item is in use; give a Ranger on the ground their normal walking pace back. */
    private static void keepWalking(LocalPlayer p) {
        if (!p.onGround() || p.isPassenger() || p.isShiftKeyDown() || p.isInWater()) {
            return;
        }
        Vec2 mv = p.input.getMoveVector();
        if (mv.x * mv.x + mv.y * mv.y < 1.0e-4f) {
            return;
        }
        double yaw = Math.toRadians(p.getYRot());
        double dx = mv.x * Math.cos(yaw) - mv.y * Math.sin(yaw);
        double dz = mv.y * Math.cos(yaw) + mv.x * Math.sin(yaw);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) {
            return;
        }
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED) * 2.15;
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(dx / len * speed, v.y, dz / len * speed);
    }
}
