package dev.gatebound.sound;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The mod's own sound events. Every sound the mod plays has a gatebound counterpart with the same path
 * (gatebound:entity.enderman.teleport), defined in sounds.json as a pair of pitch variants of the vanilla clip. To give
 * one a real sound, point its sounds.json entry at an .ogg file; no code changes. {@link #of} swaps a vanilla event for
 * its gatebound one, and leaves anything unknown as it was.
 */
public final class GateboundSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Gatebound.MOD_ID, Registries.SOUND_EVENT);
    private static final Map<String, RegistrySupplier<SoundEvent>> EVENTS = new HashMap<>();

    static {
        for (String path : SoundPaths.PATHS) {
            EVENTS.put(path, SOUNDS.register(path, () ->
                    SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path))));
        }
    }

    static {
        for (String path : CustomPaths.PATHS) {
            EVENTS.put(path, SOUNDS.register(path, () ->
                    SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path))));
        }
    }

    private GateboundSounds() {
    }

    public static void init() {
        SOUNDS.register();
    }

    /** One of the mod's own events by path (item.tome, grave.open, boss.spawn ...). */
    public static SoundEvent custom(String path) {
        return EVENTS.get(path).get();
    }

    /** The signature sound of a skill, or null when it has none and plays only its effects. */
    public static SoundEvent skill(String skillId) {
        RegistrySupplier<SoundEvent> own = EVENTS.get("skill." + skillId);
        return own == null ? null : own.get();
    }

    public static SoundEvent of(SoundEvent vanilla) {
        RegistrySupplier<SoundEvent> own = EVENTS.get(vanilla.location().getPath());
        return own != null && vanilla.location().getNamespace().equals("minecraft") ? own.get() : vanilla;
    }

    public static SoundEvent of(Holder<SoundEvent> vanilla) {
        return of(vanilla.value());
    }
}
