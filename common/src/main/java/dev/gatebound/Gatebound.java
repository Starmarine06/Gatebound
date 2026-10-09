package dev.gatebound;

import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import dev.gatebound.client.ClientHooks;
import dev.gatebound.combat.CombatHandler;
import dev.gatebound.command.GateboundCommands;
import dev.gatebound.data.Progression;
import dev.gatebound.skill.SkillHandler;
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
        dev.gatebound.config.GateboundConfig.load();
        GateboundRegistries.init();
        dev.gatebound.item.KnifeItems.init();
        dev.gatebound.item.StaffItems.init();
        dev.gatebound.item.CompoundBow.init();
        dev.gatebound.item.QuiverItem.init();
        dev.gatebound.item.GateLeaveItem.init();
        dev.gatebound.item.TrailMarkerItem.init();
        dev.gatebound.item.QuiverBack.init();
        dev.gatebound.sound.GateboundSounds.init();
        dev.gatebound.grave.GraveRegistries.init();
        dev.gatebound.mob.MobMaterials.init();
        dev.gatebound.mob.SpireMobs.init();
        // Entity types must be registered before ClientHooks registers their renderers.
        dev.gatebound.trade.NpcEntities.init();
        dev.gatebound.echo.EchoEntities.init();
        GateboundNetwork.init();
        EnvExecutor.runInEnv(Env.CLIENT, () -> ClientHooks::init);
        dev.gatebound.data.PlayerDataStore.initAutoFlush();
        Progression.init();
        // Order matters: dodge i-frames and unauthorised-hit cancelling must run before the parry handler.
        dev.gatebound.network.Combat4bNetwork.init();
        dev.gatebound.combat.PlayerCombat.init();
        CombatHandler.init();
        SkillHandler.init();
        dev.gatebound.skill.RogueSkills.init();
        dev.gatebound.skill.WarriorSkills.init();
        dev.gatebound.skill.mage.MageCore.init();
        dev.gatebound.skill.RangerSkills.init();
        dev.gatebound.dungeon.DungeonManager.init();
        GateboundCommands.init();
        dev.gatebound.trade.NpcHandler.init();
        dev.gatebound.echo.EchoManager.init();
        LOGGER.info("Gatebound initialised");
    }
}
