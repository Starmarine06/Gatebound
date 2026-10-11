package dev.gatebound.skill;

import static dev.gatebound.skill.fx.Tasks.sound;
import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.data.Element;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.Stat;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import dev.gatebound.skill.mage.DarkSkills;
import dev.gatebound.skill.mage.EarthSkills;
import dev.gatebound.skill.mage.FireSkills;
import dev.gatebound.skill.mage.IceSkills;
import dev.gatebound.skill.mage.LightSkills;
import dev.gatebound.skill.mage.LightningSkills;
import dev.gatebound.skill.mage.WindSkills;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The skill registry. Summoner, Rogue, Warrior and Ranger have full ladders to level 100.
 * Mages get two skills per element they hold, so a Mage with several elements can have up to six or more.
 */
public final class Skills {
    public static final List<Skill> ALL = new ArrayList<>();

    // Warrior
    public static final Skill SHOCKWAVE = add(new Skill("shockwave", GateboundClass.WARRIOR, null, 1, WarriorSkills.SHOCKWAVE_COST, 160, WarriorSkills::shockwave));
    public static final Skill CHARGE = add(new Skill("charge", GateboundClass.WARRIOR, null, 3, WarriorSkills.CHARGE_COST, 120, WarriorSkills::charge));
    public static final Skill WAR_CRY = add(new Skill("war_cry", GateboundClass.WARRIOR, null, 5, WarriorSkills.WAR_CRY_COST, 400, WarriorSkills::warCry));
    public static final Skill CLEAVE = add(new Skill("cleave", GateboundClass.WARRIOR, null, 8, WarriorSkills.CLEAVE_COST, 100, WarriorSkills::cleave));
    public static final Skill IRON_SKIN = add(new Skill("iron_skin", GateboundClass.WARRIOR, null, 10, WarriorSkills.IRON_SKIN_COST, 300, WarriorSkills::ironSkin));
    public static final Skill LEAP_SLAM = add(new Skill("leap_slam", GateboundClass.WARRIOR, null, 13, WarriorSkills.LEAP_COST, 200, WarriorSkills::leapSlam));
    public static final Skill RENDING_STRIKE = add(new Skill("rending_strike", GateboundClass.WARRIOR, null, 16, WarriorSkills.RENDING_COST, 160, WarriorSkills::rendingStrike));
    public static final Skill BULWARK = add(new Skill("bulwark", GateboundClass.WARRIOR, null, 20, WarriorSkills.BULWARK_COST, 400, WarriorSkills::bulwark));
    public static final Skill WHIRLWIND = add(new Skill("whirlwind", GateboundClass.WARRIOR, null, 27, WarriorSkills.WHIRLWIND_COST, 240, WarriorSkills::whirlwind));
    public static final Skill SECOND_WIND = add(new Skill("second_wind", GateboundClass.WARRIOR, null, 35, WarriorSkills.SECOND_WIND_COST, 600, WarriorSkills::secondWind));
    public static final Skill CRUSHING_BLOW = add(new Skill("crushing_blow", GateboundClass.WARRIOR, null, 45, WarriorSkills.CRUSHING_COST, 200, WarriorSkills::crushingBlow));
    public static final Skill BERSERKER_RAGE = add(new Skill("berserker_rage", GateboundClass.WARRIOR, null, 60, WarriorSkills.RAGE_COST, 0, WarriorSkills::berserkerRage));
    public static final Skill EARTHBREAKER = add(new Skill("earthbreaker", GateboundClass.WARRIOR, null, 80, WarriorSkills.EARTHBREAKER_COST, 0, WarriorSkills::earthbreaker));
    public static final Skill AVATAR_OF_WAR = add(new Skill("avatar_of_war", GateboundClass.WARRIOR, null, 100, WarriorSkills.AVATAR_COST, 0, WarriorSkills::avatarOfWar));
    // Rogue
    public static final Skill SHADOW_STEP = add(new Skill("shadow_step", GateboundClass.ROGUE, null, 1, RogueSkills.STEP_COST, 0, RogueSkills::shadowStep));
    public static final Skill THROWING_KNIVES = add(new Skill("throwing_knives", GateboundClass.ROGUE, null, 3, RogueSkills.KNIVES_COST, 0, RogueSkills::throwingKnives));
    public static final Skill VANISH = add(new Skill("vanish", GateboundClass.ROGUE, null, 5, RogueSkills.VANISH_COST, 0, RogueSkills::vanish));
    public static final Skill MARK_FOR_DEATH = add(new Skill("mark_for_death", GateboundClass.ROGUE, null, 8, RogueSkills.MARK_COST, 0, RogueSkills::markForDeath));
    public static final Skill SHADOW_MELD = add(new Skill("shadow_meld", GateboundClass.ROGUE, null, 10, RogueSkills.MELD_COST, 0, RogueSkills::shadowMeld));
    public static final Skill VENOM_BLADE = add(new Skill("venom_blade", GateboundClass.ROGUE, null, 11, RogueSkills.VENOM_COST, 0, RogueSkills::venomBlade));
    public static final Skill BACKSTAB = add(new Skill("backstab", GateboundClass.ROGUE, null, 15, RogueSkills.BACKSTAB_COST, 0, RogueSkills::backstab));
    public static final Skill SMOKE_BOMB = add(new Skill("smoke_bomb", GateboundClass.ROGUE, null, 20, RogueSkills.SMOKE_COST, 0, RogueSkills::smokeBomb));
    public static final Skill FAN_OF_KNIVES = add(new Skill("fan_of_knives", GateboundClass.ROGUE, null, 27, RogueSkills.FAN_COST, 0, RogueSkills::fanOfKnives));
    public static final Skill BLADE_DANCE = add(new Skill("blade_dance", GateboundClass.ROGUE, null, 35, RogueSkills.DANCE_BLADES_COST, 0, RogueSkills::bladeDance));
    public static final Skill EXECUTE = add(new Skill("execute", GateboundClass.ROGUE, null, 45, RogueSkills.EXECUTE_COST, 0, RogueSkills::execute));
    public static final Skill SHADOW_DANCE = add(new Skill("shadow_dance", GateboundClass.ROGUE, null, 60, RogueSkills.SHADOW_DANCE_COST, 0, RogueSkills::shadowDance));
    public static final Skill THOUSAND_CUTS = add(new Skill("thousand_cuts", GateboundClass.ROGUE, null, 80, RogueSkills.CUTS_COST, 0, RogueSkills::thousandCuts));
    public static final Skill SHADOW_POSSESSION = add(new Skill("shadow_possession", GateboundClass.ROGUE, null, 100, RogueSkills.POSSESSION_COST, 0, RogueSkills::shadowPossession));
    // Ranger
    public static final Skill EAGLE_EYE = add(new Skill("eagle_eye", GateboundClass.RANGER, null, 1, RangerSkills.EAGLE_COST, 300, RangerSkills::eagleEye));
    public static final Skill HOMING_SHOT = add(new Skill("homing_shot", GateboundClass.RANGER, null, 3, RangerSkills.HOMING_COST, 60, RangerSkills::homingShot));
    public static final Skill PIERCING_SHOT = add(new Skill("piercing_shot", GateboundClass.RANGER, null, 5, RangerSkills.PIERCING_COST, 120, RangerSkills::piercingShot));
    public static final Skill HUNTERS_QUARRY = add(new Skill("hunters_quarry", GateboundClass.RANGER, null, 8, RangerSkills.QUARRY_COST, 60, RangerSkills::huntersQuarry));
    public static final Skill EVASIVE_ROLL = add(new Skill("evasive_roll", GateboundClass.RANGER, null, 10, RangerSkills.ROLL_COST, 100, RangerSkills::evasiveRoll));
    public static final Skill POISON_ARROW = add(new Skill("poison_arrow", GateboundClass.RANGER, null, 13, RangerSkills.POISON_COST, 100, RangerSkills::poisonArrow));
    public static final Skill SNARE_ARROW = add(new Skill("snare_arrow", GateboundClass.RANGER, null, 16, RangerSkills.SNARE_COST, 140, RangerSkills::snareArrow));
    public static final Skill MULTISHOT = add(new Skill("multishot", GateboundClass.RANGER, null, 20, RangerSkills.MULTISHOT_COST, 120, RangerSkills::multishot));
    public static final Skill RICOCHET = add(new Skill("ricochet", GateboundClass.RANGER, null, 27, RangerSkills.RICOCHET_COST, 140, RangerSkills::ricochet));
    public static final Skill RAIN_OF_ARROWS = add(new Skill("rain_of_arrows", GateboundClass.RANGER, null, 35, RangerSkills.RAIN_COST, 300, RangerSkills::rainOfArrows));
    public static final Skill HEADSHOT = add(new Skill("headshot", GateboundClass.RANGER, null, 45, RangerSkills.HEADSHOT_COST, 200, RangerSkills::headshot));
    public static final Skill HUNTERS_TRANCE = add(new Skill("hunters_trance", GateboundClass.RANGER, null, 60, RangerSkills.TRANCE_COST, 0, RangerSkills::huntersTrance));
    public static final Skill ARROW_BARRAGE = add(new Skill("arrow_barrage", GateboundClass.RANGER, null, 80, RangerSkills.BARRAGE_COST, 0, RangerSkills::arrowBarrage));
    public static final Skill APEX_PREDATOR = add(new Skill("apex_predator", GateboundClass.RANGER, null, 100, RangerSkills.APEX_COST, 0, RangerSkills::apexPredator));
    // Summoner
    public static final Skill SPIRIT_WARD = add(new Skill("spirit_ward", GateboundClass.SUMMONER, null, 1, 14, 240, Skills::spiritWard));
    public static final Skill SCOUTS_EYE = add(new Skill("scouts_eye", GateboundClass.SUMMONER, null, 3, 12, 0, dev.gatebound.echo.EchoView::cast));
    public static final Skill SOUL_DRAIN = add(new Skill("soul_drain", GateboundClass.SUMMONER, null, 5, 16, 140, Skills::soulDrain));
    public static final Skill RALLY = add(new Skill("rally", GateboundClass.SUMMONER, null, 7, dev.gatebound.echo.EchoSkills.RALLY_COST, 100, dev.gatebound.echo.EchoSkills::rally));
    public static final Skill COMMAND_STRIKE = add(new Skill("command_strike", GateboundClass.SUMMONER, null, 10, dev.gatebound.echo.EchoSkills.COMMAND_COST, 120, dev.gatebound.echo.EchoSkills::commandStrike));
    public static final Skill EMPOWER = add(new Skill("empower", GateboundClass.SUMMONER, null, 13, dev.gatebound.echo.EchoSkills.EMPOWER_COST, 400, dev.gatebound.echo.EchoSkills::empower));
    public static final Skill SOUL_LINK = add(new Skill("soul_link", GateboundClass.SUMMONER, null, 16, dev.gatebound.echo.EchoSkills.LINK_COST, 0, dev.gatebound.echo.EchoSkills::soulLink));
    public static final Skill SACRIFICE = add(new Skill("sacrifice", GateboundClass.SUMMONER, null, 20, dev.gatebound.echo.EchoSkills.SACRIFICE_COST, 0, dev.gatebound.echo.EchoSkills::sacrifice));
    public static final Skill SOUL_SWAP = add(new Skill("soul_swap", GateboundClass.SUMMONER, null, 28, dev.gatebound.echo.EchoSkills.SWAP_COST, 0, dev.gatebound.echo.EchoSkills::soulSwap));
    public static final Skill BIND_CHAINS = add(new Skill("bind_chains", GateboundClass.SUMMONER, null, 35, dev.gatebound.echo.EchoSkills.CHAINS_COST, 0, dev.gatebound.echo.EchoSkills::bindChains));
    public static final Skill LEGION_CALL = add(new Skill("legion_call", GateboundClass.SUMMONER, null, 45, dev.gatebound.echo.EchoSkills.LEGION_COST, 0, dev.gatebound.echo.EchoSkills::legionCall));
    public static final Skill SOUL_STORM = add(new Skill("soul_storm", GateboundClass.SUMMONER, null, 60, dev.gatebound.echo.EchoSkills.STORM_COST, 0, dev.gatebound.echo.EchoSkills::soulStorm));
    public static final Skill ASCENSION = add(new Skill("ascension", GateboundClass.SUMMONER, null, 80, dev.gatebound.echo.EchoSkills.ASCENSION_COST, 0, dev.gatebound.echo.EchoSkills::ascension));
    public static final Skill SOVEREIGNS_DOMAIN = add(new Skill("sovereigns_domain", GateboundClass.SUMMONER, null, 100, dev.gatebound.echo.EchoSkills.DOMAIN_COST, 0, dev.gatebound.echo.EchoSkills::sovereignsDomain));

    // Mage: Fire
    public static final Skill FIREBALL = add(new Skill("fireball", GateboundClass.MAGE, Element.FIRE, 1, FireSkills.FIREBALL_COST, 0, FireSkills::fireball));
    public static final Skill FLAME_BURST = add(new Skill("flame_burst", GateboundClass.MAGE, Element.FIRE, 5, FireSkills.BURST_COST, 0, FireSkills::flameBurst));
    public static final Skill IGNITE = add(new Skill("ignite", GateboundClass.MAGE, Element.FIRE, 10, FireSkills.IGNITE_COST, 0, FireSkills::ignite));
    public static final Skill FLAME_WALL = add(new Skill("flame_wall", GateboundClass.MAGE, Element.FIRE, 18, FireSkills.WALL_COST, 0, FireSkills::flameWall));
    public static final Skill IMMOLATE = add(new Skill("immolate", GateboundClass.MAGE, Element.FIRE, 28, FireSkills.IMMOLATE_COST, 0, FireSkills::immolate));
    public static final Skill COMBUSTION = add(new Skill("combustion", GateboundClass.MAGE, Element.FIRE, 40, FireSkills.COMBUSTION_COST, 0, FireSkills::combustion));
    public static final Skill METEOR = add(new Skill("meteor", GateboundClass.MAGE, Element.FIRE, 60, FireSkills.METEOR_COST, 0, FireSkills::meteor));
    public static final Skill INFERNO = add(new Skill("inferno", GateboundClass.MAGE, Element.FIRE, 85, FireSkills.INFERNO_COST, 0, FireSkills::inferno));
    public static final Skill IFRIT_FORM = add(new Skill("ifrit_form", GateboundClass.MAGE, Element.FIRE, 100, FireSkills.IFRIT_COST, 0, FireSkills::ifritForm));
    // Mage: Ice
    public static final Skill FROST_SHARD = add(new Skill("frost_shard", GateboundClass.MAGE, Element.ICE, 1, IceSkills.SHARD_COST, 0, IceSkills::frostShard));
    public static final Skill FROST_NOVA = add(new Skill("frost_nova", GateboundClass.MAGE, Element.ICE, 5, IceSkills.NOVA_COST, 0, IceSkills::frostNova));
    public static final Skill ICE_ARMOR = add(new Skill("ice_armor", GateboundClass.MAGE, Element.ICE, 10, IceSkills.ARMOR_COST, 0, IceSkills::iceArmor));
    public static final Skill GLACIAL_PATH = add(new Skill("glacial_path", GateboundClass.MAGE, Element.ICE, 18, IceSkills.PATH_COST, 0, IceSkills::glacialPath));
    public static final Skill ICICLE_BARRAGE = add(new Skill("icicle_barrage", GateboundClass.MAGE, Element.ICE, 28, IceSkills.BARRAGE_COST, 0, IceSkills::icicleBarrage));
    public static final Skill SHATTER = add(new Skill("shatter", GateboundClass.MAGE, Element.ICE, 40, IceSkills.SHATTER_COST, 0, IceSkills::shatter));
    public static final Skill BLIZZARD = add(new Skill("blizzard", GateboundClass.MAGE, Element.ICE, 60, IceSkills.BLIZZARD_COST, 0, IceSkills::blizzard));
    public static final Skill ABSOLUTE_ZERO = add(new Skill("absolute_zero", GateboundClass.MAGE, Element.ICE, 85, IceSkills.ZERO_COST, 0, IceSkills::absoluteZero));
    public static final Skill FROST_SOVEREIGN = add(new Skill("frost_sovereign", GateboundClass.MAGE, Element.ICE, 100, IceSkills.SOVEREIGN_COST, 0, IceSkills::frostSovereign));
    // Mage: Lightning
    public static final Skill ARC_BOLT = add(new Skill("arc_bolt", GateboundClass.MAGE, Element.LIGHTNING, 1, LightningSkills.ARC_COST, 0, LightningSkills::arcBolt));
    public static final Skill THUNDER_STORM = add(new Skill("thunder_storm", GateboundClass.MAGE, Element.LIGHTNING, 5, LightningSkills.STORM_COST, 0, LightningSkills::thunderStorm));
    public static final Skill STATIC_STEP = add(new Skill("static_step", GateboundClass.MAGE, Element.LIGHTNING, 10, LightningSkills.STEP_COST, 0, LightningSkills::staticStep));
    public static final Skill OVERCHARGE = add(new Skill("overcharge", GateboundClass.MAGE, Element.LIGHTNING, 18, LightningSkills.OVERCHARGE_COST, 0, LightningSkills::overcharge));
    public static final Skill CHAIN_LIGHTNING = add(new Skill("chain_lightning", GateboundClass.MAGE, Element.LIGHTNING, 28, LightningSkills.CHAIN_COST, 0, LightningSkills::chainLightning));
    public static final Skill LIGHTNING_ROD = add(new Skill("lightning_rod", GateboundClass.MAGE, Element.LIGHTNING, 40, LightningSkills.ROD_COST, 0, LightningSkills::lightningRod));
    public static final Skill THUNDERSTRIKE = add(new Skill("thunderstrike", GateboundClass.MAGE, Element.LIGHTNING, 60, LightningSkills.STRIKE_COST, 0, LightningSkills::thunderstrike));
    public static final Skill STORM_SURGE = add(new Skill("storm_surge", GateboundClass.MAGE, Element.LIGHTNING, 85, LightningSkills.SURGE_COST, 0, LightningSkills::stormSurge));
    public static final Skill THUNDER_GOD = add(new Skill("thunder_god", GateboundClass.MAGE, Element.LIGHTNING, 100, LightningSkills.GOD_COST, 0, LightningSkills::thunderGod));
    // Mage: Wind
    public static final Skill GUST = add(new Skill("gust", GateboundClass.MAGE, Element.WIND, 1, WindSkills.GUST_COST, 0, WindSkills::gust));
    public static final Skill CYCLONE = add(new Skill("cyclone", GateboundClass.MAGE, Element.WIND, 5, WindSkills.CYCLONE_COST, 0, WindSkills::cyclone));
    public static final Skill AIR_DASH = add(new Skill("air_dash", GateboundClass.MAGE, Element.WIND, 10, WindSkills.DASH_COST, 0, WindSkills::airDash));
    public static final Skill UPDRAFT = add(new Skill("updraft", GateboundClass.MAGE, Element.WIND, 18, WindSkills.UPDRAFT_COST, 0, WindSkills::updraft));
    public static final Skill WIND_BLADES = add(new Skill("wind_blades", GateboundClass.MAGE, Element.WIND, 28, WindSkills.BLADES_COST, 0, WindSkills::windBlades));
    public static final Skill WIND_BARRIER = add(new Skill("wind_barrier", GateboundClass.MAGE, Element.WIND, 40, WindSkills.BARRIER_COST, 0, WindSkills::windBarrier));
    public static final Skill TORNADO = add(new Skill("tornado", GateboundClass.MAGE, Element.WIND, 60, WindSkills.TORNADO_COST, 0, WindSkills::tornado));
    public static final Skill TEMPEST_FLIGHT = add(new Skill("tempest_flight", GateboundClass.MAGE, Element.WIND, 85, WindSkills.FLIGHT_COST, 0, WindSkills::tempestFlight));
    public static final Skill WIND_AVATAR = add(new Skill("wind_avatar", GateboundClass.MAGE, Element.WIND, 100, WindSkills.AVATAR_COST, 0, WindSkills::windAvatar));
    // Mage: Earth
    public static final Skill STONE_SPIKE = add(new Skill("stone_spike", GateboundClass.MAGE, Element.EARTH, 1, EarthSkills.SPIKE_COST, 0, EarthSkills::stoneSpike));
    public static final Skill STONE_SKIN = add(new Skill("stone_skin", GateboundClass.MAGE, Element.EARTH, 5, EarthSkills.SKIN_COST, 0, EarthSkills::stoneSkin));
    public static final Skill STONE_WALL = add(new Skill("stone_wall", GateboundClass.MAGE, Element.EARTH, 10, EarthSkills.WALL_COST, 0, EarthSkills::stoneWall));
    public static final Skill TREMOR = add(new Skill("tremor", GateboundClass.MAGE, Element.EARTH, 18, EarthSkills.TREMOR_COST, 0, EarthSkills::tremor));
    public static final Skill BOULDER = add(new Skill("boulder", GateboundClass.MAGE, Element.EARTH, 28, EarthSkills.BOULDER_COST, 0, EarthSkills::boulder));
    public static final Skill BEDROCK = add(new Skill("bedrock", GateboundClass.MAGE, Element.EARTH, 40, EarthSkills.BEDROCK_COST, 0, EarthSkills::bedrock));
    public static final Skill EARTHQUAKE = add(new Skill("earthquake", GateboundClass.MAGE, Element.EARTH, 60, EarthSkills.QUAKE_COST, 0, EarthSkills::earthquake));
    public static final Skill LANDSLIDE = add(new Skill("landslide", GateboundClass.MAGE, Element.EARTH, 85, EarthSkills.LANDSLIDE_COST, 0, EarthSkills::landslide));
    public static final Skill TITAN_FORM = add(new Skill("titan_form", GateboundClass.MAGE, Element.EARTH, 100, EarthSkills.TITAN_COST, 0, EarthSkills::titanForm));
    // Mage: Light
    public static final Skill RADIANT_BOLT = add(new Skill("radiant_bolt", GateboundClass.MAGE, Element.LIGHT, 1, LightSkills.BOLT_COST, 0, LightSkills::radiantBolt));
    public static final Skill SANCTUARY = add(new Skill("sanctuary", GateboundClass.MAGE, Element.LIGHT, 5, LightSkills.SANCTUARY_COST, 0, LightSkills::sanctuary));
    public static final Skill MENDING_LIGHT = add(new Skill("mending_light", GateboundClass.MAGE, Element.LIGHT, 10, LightSkills.MENDING_COST, 0, LightSkills::mendingLight));
    public static final Skill BLINDING_FLASH = add(new Skill("blinding_flash", GateboundClass.MAGE, Element.LIGHT, 18, LightSkills.FLASH_COST, 0, LightSkills::blindingFlash));
    public static final Skill HOLY_SMITE = add(new Skill("holy_smite", GateboundClass.MAGE, Element.LIGHT, 28, LightSkills.SMITE_COST, 0, LightSkills::holySmite));
    public static final Skill AEGIS = add(new Skill("aegis", GateboundClass.MAGE, Element.LIGHT, 40, LightSkills.AEGIS_COST, 0, LightSkills::aegis));
    public static final Skill JUDGEMENT = add(new Skill("judgement", GateboundClass.MAGE, Element.LIGHT, 60, LightSkills.JUDGEMENT_COST, 0, LightSkills::judgement));
    public static final Skill RESURRECTION = add(new Skill("resurrection", GateboundClass.MAGE, Element.LIGHT, 85, LightSkills.RESURRECTION_COST, 0, LightSkills::resurrection));
    public static final Skill SERAPH_FORM = add(new Skill("seraph_form", GateboundClass.MAGE, Element.LIGHT, 100, LightSkills.SERAPH_COST, 0, LightSkills::seraphForm));
    // Mage: Dark
    public static final Skill SHADOW_BOLT = add(new Skill("shadow_bolt", GateboundClass.MAGE, Element.DARK, 1, DarkSkills.BOLT_COST, 0, DarkSkills::shadowBolt));
    public static final Skill VOID_GRASP = add(new Skill("void_grasp", GateboundClass.MAGE, Element.DARK, 5, DarkSkills.GRASP_COST, 0, DarkSkills::voidGrasp));
    public static final Skill HEX = add(new Skill("hex", GateboundClass.MAGE, Element.DARK, 10, DarkSkills.HEX_COST, 0, DarkSkills::hex));
    public static final Skill LIFE_SIPHON = add(new Skill("life_siphon", GateboundClass.MAGE, Element.DARK, 18, DarkSkills.SIPHON_COST, 0, DarkSkills::lifeSiphon));
    public static final Skill DREAD = add(new Skill("dread", GateboundClass.MAGE, Element.DARK, 28, DarkSkills.DREAD_COST, 0, DarkSkills::dread));
    public static final Skill SOUL_HARVEST = add(new Skill("soul_harvest", GateboundClass.MAGE, Element.DARK, 40, DarkSkills.HARVEST_COST, 0, DarkSkills::soulHarvest));
    public static final Skill BLACK_HOLE = add(new Skill("black_hole", GateboundClass.MAGE, Element.DARK, 60, DarkSkills.HOLE_COST, 0, DarkSkills::blackHole));
    public static final Skill ECLIPSE = add(new Skill("eclipse", GateboundClass.MAGE, Element.DARK, 85, DarkSkills.ECLIPSE_COST, 0, DarkSkills::eclipse));
    public static final Skill VOID_LORD = add(new Skill("void_lord", GateboundClass.MAGE, Element.DARK, 100, DarkSkills.VOID_COST, 0, DarkSkills::voidLord));

    /** Static holder; not instantiable. */
    private Skills() {
    }

    /** Registers a skill in the list. */
    private static Skill add(Skill s) {
        ALL.add(s);
        return s;
    }

    /** Skill by id, or null. */
    public static Skill byId(String id) {
        for (Skill s : ALL) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Skills the given class, elements and level can use, in slot order.
     * Mages: grouped by element in the order the elements were gained, lowest level first within each.
     */
    public static List<Skill> available(String classId, List<String> elements, List<String> picks, int level) {
        GateboundClass cls = GateboundClass.byId(classId);
        List<Skill> out = new ArrayList<>();
        if (cls == null) {
            return out;
        }
        if (cls != GateboundClass.MAGE) {
            for (Skill s : ALL) {
                if (s.owner() == cls && level >= s.minLevel()) {
                    out.add(s);
                }
            }
            return out;
        }
        for (String elementId : elements) {
            Element element = Element.byId(elementId);
            for (Skill s : ALL) {
                if (s.owner() == GateboundClass.MAGE && s.element() == element && level >= s.minLevel()
                        && SpellPicks.unlocked(s, elements.size(), picks)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ---- Effects ---------------------------------------------------------------------------------------------

    /** Magic damage source attributed to the player. */
    private static DamageSource magic(ServerPlayer player, ServerLevel level) {
        return level.damageSources().indirectMagic(player, player);
    }

    /** Base value plus a scale times the player's intellect. */
    private static float intel(PlayerData data, double base, double scale) {
        return (float) (base + scale * data.get(Stat.INTELLECT));
    }

    // Summoner

    /** Spirit Ward: heals the Summoner, grants absorption and wisps. */
    private static boolean spiritWard(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.heal(intel(data, 6, 0.5));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        dev.gatebound.skill.fx.SummonerFx.spiritWard(player, 200);
        sound(player, level, GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 1.0f);
        return true;
    }

    /** Soul Drain: damages the target and heals the Summoner for half. */
    private static boolean soulDrain(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 16, 1, ParticleTypes.SOUL);
        sound(player, level, GateboundSounds.of(SoundEvents.SOUL_ESCAPE.value()), 1.0f);
        if (hits.isEmpty()) {
            return false;
        }
        float damage = intel(data, 4, 0.6);
        dev.gatebound.skill.fx.SummonerFx.drain(player, hits.get(0));
        hits.get(0).hurtServer(level, magic(player, level), dev.gatebound.skill.Proficiency.damage(player, damage));
        player.heal(damage * 0.5f);
        return true;
    }

}
