package dev.gatebound;

import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import dev.gatebound.client.ClientHooks;
import dev.gatebound.combat.CombatHandler;
import dev.gatebound.command.GateboundCommands;
import dev.gatebound.data.Progression;
import dev.gatebound.network.GateboundNetwork;
import dev.gatebound.registry.GateboundRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Gatebound {
    public static final String MOD_ID = "gatebound";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Gatebound() {
    }

    public static void init() {
        GateboundRegistries.init();
        GateboundNetwork.init();
        EnvExecutor.runInEnv(Env.CLIENT, () -> ClientHooks::init);
        Progression.init();
        CombatHandler.init();
        GateboundCommands.init();
        LOGGER.info("Gatebound initialised");
    }
}
