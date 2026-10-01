package dev.gatebound.neoforge;

import dev.gatebound.Gatebound;
import net.neoforged.fml.common.Mod;

@Mod(Gatebound.MOD_ID)
public final class GateboundNeoForge {
    public GateboundNeoForge() {
        Gatebound.init();
    }
}
