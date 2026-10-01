package dev.gatebound.fabric;

import dev.gatebound.Gatebound;
import net.fabricmc.api.ModInitializer;

public final class GateboundFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Gatebound.init();
    }
}
