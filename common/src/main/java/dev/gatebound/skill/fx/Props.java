package dev.gatebound.skill.fx;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Locale;
import java.util.UUID;

/**
 * Short-lived 3D props: vanilla block display entities that scale, spin and glide smoothly on the client through transformation
 * interpolation. They are created and updated with commands (the one interface that is the same in every Minecraft version),
 * carry the tag {@code gatebound_fx}, and are always removed: by their own timer, when the server stops or starts, and when
 * a player joins.
 */
public final class Props {
    private static final String TAG = "gatebound_fx";

    private Props() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STOPPING.register(Props::sweep);
        LifecycleEvent.SERVER_STARTED.register(Props::sweep);
        PlayerEvent.PLAYER_JOIN.register(player -> sweep(player.level().getServer()));
    }

    private static void sweep(MinecraftServer server) {
        run(server, null, null, "kill @e[tag=" + TAG + "]");
    }

    private static void run(MinecraftServer server, ServerLevel level, Vec3 at, String command) {
        net.minecraft.commands.CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        if (level != null) {
            source = source.withLevel(level);
        }
        if (at != null) {
            source = source.withPosition(at);
        }
        try {
            server.getCommands().getDispatcher().execute(command, source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // A prop that fails to appear must not break the spell; the log says why.
            dev.gatebound.Gatebound.LOGGER.warn("Prop command failed ({}): {}", e.getMessage(), command);
        }
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3ff", v);
    }

    private static String uuidNbt(UUID id) {
        long m = id.getMostSignificantBits();
        long l = id.getLeastSignificantBits();
        return "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
    }

    /** A prop in the world. Move and reshape it with {@link #to}; it removes itself after its lifetime. */
    public static final class Prop {
        private final ServerLevel level;
        private Vec3 origin;
        private final UUID id = UUID.randomUUID();
        private boolean gone;

        private Prop(ServerLevel level, Vec3 origin) {
            this.level = level;
            this.origin = origin;
        }

        /**
         * Glides to a new offset, size and spin over {@code ticks}. Offsets are relative to where the prop was placed;
         * {@code spinDeg} turns it around the vertical axis.
         */
        public void to(Vec3 offset, double sx, double sy, double sz, double spinDeg, int ticks) {
            if (gone) {
                return;
            }
            run(level.getServer(), level, origin, "data merge entity " + id + " " + transformation(offset, sx, sy, sz, spinDeg)
                    + ",start_interpolation:0,interpolation_duration:" + ticks + "}");
        }

        public void remove() {
            if (gone) {
                return;
            }
            gone = true;
            run(level.getServer(), level, origin, "kill " + id);
        }

        /**
         * Poses a model prop: glides to an offset from where it was placed, turns to {@code rotation} and resizes, over
         * {@code ticks}. A model prop is centred on its position, so the offset is where its middle goes.
         */
        public void pose(Vec3 offset, Quaternionf rotation, double sx, double sy, double sz, int ticks) {
            if (gone) {
                return;
            }
            run(level.getServer(), level, origin, "data merge entity " + id + " {transformation:{left_rotation:[" + num(rotation.x) + ","
                    + num(rotation.y) + "," + num(rotation.z) + "," + num(rotation.w) + "],right_rotation:[0f,0f,0f,1f],scale:["
                    + num(sx) + "," + num(sy) + "," + num(sz) + "],translation:[" + num(offset.x) + "," + num(offset.y) + "," + num(offset.z)
                    + "]},start_interpolation:0,interpolation_duration:" + ticks + "}");
        }

        /**
         * Poses a model prop so its middle ends up at the world position {@code target}. A prop that has drifted far from
         * where it was placed is moved there first, so it never leaves the range the client draws it in.
         */
        public void pin(Vec3 target, Quaternionf rotation, double sx, double sy, double sz, int ticks) {
            if (gone) {
                return;
            }
            if (target.distanceToSqr(origin) > 20 * 20) {
                origin = target;
                run(level.getServer(), level, target, "tp " + id + " ~ ~ ~");
            }
            pose(target.subtract(origin), rotation, sx, sy, sz, ticks);
        }

        public Vec3 origin() {
            return origin;
        }

        /** Removes the prop after a delay. */
        public void removeAfter(int ticks) {
            Tasks.after(ticks, server -> remove());
        }
    }

    private static String transformation(Vec3 off, double sx, double sy, double sz, double spinDeg) {
        double half = Math.toRadians(spinDeg) / 2;
        // Rotation about the vertical axis as a quaternion [x, y, z, w]; the scale is applied before it, then the offset.
        return "{transformation:{left_rotation:[0f," + num(Math.sin(half)) + ",0f," + num(Math.cos(half)) + "],right_rotation:[0f,0f,0f,1f],"
                + "scale:[" + num(sx) + "," + num(sy) + "," + num(sz) + "],translation:[" + num(off.x - sx / 2) + "," + num(off.y) + ","
                + num(off.z - sz / 2) + "]}";
    }

    /**
     * Places a prop that shows one of the mod's effect models (assets/gatebound/items/fx/NAME.json), fully bright, at
     * {@code at}. It starts invisible-small; pose it with {@link Prop#pose}. It is removed after {@code lifetime} ticks at most.
     * The model is chosen with the item_model component on a plain item, so no item has to be registered.
     */
    public static Prop sprite(ServerLevel level, Vec3 at, String model, int lifetime) {
        Prop prop = new Prop(level, at);
        String nbt = "{UUID:" + uuidNbt(prop.id) + ",Tags:[\"" + TAG + "\"],item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":\"gatebound:fx/"
                + model + "\"}},item_display:\"none\",brightness:{block:15,sky:15},width:128f,height:128f,view_range:2.0f,shadow_radius:0f,"
                + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[0.05f,0.05f,0.05f],translation:[0f,0f,0f]}}";
        run(level.getServer(), level, at, "summon minecraft:item_display ~ ~ ~ " + nbt);
        prop.removeAfter(lifetime);
        return prop;
    }

    /**
     * Places a glowing block prop whose base sits at {@code at}, {@code lifetime} ticks long at most. It starts at the given
     * size; reshape it with {@link Prop#to}.
     */
    public static Prop block(ServerLevel level, Vec3 at, String blockId, double sx, double sy, double sz, int lifetime) {
        Prop prop = new Prop(level, at);
        String nbt = "{UUID:" + uuidNbt(prop.id) + ",Tags:[\"" + TAG + "\"],block_state:{Name:\"" + blockId + "\"},"
                + "brightness:{block:15,sky:15},width:48f,height:48f,view_range:2.0f,shadow_radius:0f,"
                + transformation(Vec3.ZERO, sx, sy, sz, 0).substring(1) + "}";
        run(level.getServer(), level, at, "summon minecraft:block_display ~ ~ ~ " + nbt);
        prop.removeAfter(lifetime);
        return prop;
    }
}
