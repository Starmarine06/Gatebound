# Gatebound: handoff notes

Last updated 2026-10-05. Read this first when picking the project back up.

## What this is
An original isekai-RPG mod for **Minecraft 26.3**, for **Fabric and NeoForge** only, built with **Architectury** (API 22.0.3).
Package `dev.gatebound`, mod id `gatebound`. Theme: Solo Leveling / DanMachi / SAO style (classes, levelling, stats, skills and
magic, parry combat, a procedural tower, gates that break open, minions called Echoes, NPC trading).

Repo: `X:\Mods\AdventureTime` (git, branch `master`). Everything below is committed. Last commit before this file: the gate
dungeon / leak change.

## Build and run
- JDK 25 (Temurin). Gradle 9.7.1. Commands: `./gradlew build`, `:fabric:runClient`, `:fabric:runServer`, `:neoforge:runClient`, `:neoforge:runServer`.
- IntelliJ run configs live in `.run/`.
- **Close the game client before building.** A running client locks `common/build/libs/gatebound-common-0.1.0-dev.jar`
  and the build fails with "Failed to modify jar manifest". `./gradlew :common:compileJava` still works while the game is open.
- The dev client username is pinned to **Player896** (`loom.runs.client.programArgs` in `fabric/build.gradle` and `neoforge/build.gradle`).
  Offline UUIDs come from the name, so a stable name keeps the same player data between runs.
- Progress is flushed to disk about once a second (`PlayerDataStore.initAutoFlush`), so stopping the client from the IDE no longer loses it.
- Minecraft 26.3 is unobfuscated (Mojang names). Verify APIs with `javap` against
  `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf-26.3/...jar` (the 26.x API differs from older tutorials:
  `Identifier` not `ResourceLocation`, `extractRenderState` screens, `hurtServer`, `EntityTypes`, `Blocks.CARPET.red()` colour collections, etc.).

## Gotchas for whoever edits this (including me)
- **Minecraft 26.x reads the keyboard through SDL.** `InputConstants.KEY_Z` is 29 (a scancode), not GLFW's 90. Never hard-code raw GLFW numbers for key defaults;
  use `InputConstants.KEY_*`. Held-key state: `InputConstants.isKeyDown(key.getValue())` (see `ClientHooks.wheelHeld`). Key events repeat while held, so do not open/close screens from them.
- `PoseStack` has `rotateDegrees(Axis, float)` (no `mulPose(Quaternion)`).
- In the Bash tool, long heredocs containing quotes tend to break ("unexpected EOF"). Write scripts with the Write tool into the scratchpad
  and run them with `python`, or use Edit.
- The python edit scripts used `str.replace` with exact old text; when a file changes, re-read it first.

## Controls (all rebindable under Controls > Gatebound)
| Action | Default |
|---|---|
| Status window (stats, spend points) | Tab |
| Parry | Mouse button 5 |
| Dodge | Left Alt |
| Skills: tap = cast the selected skill; hold = wheel, point, release = cast that skill | Z |
| Summon / dismiss Echoes | R (not G: vanilla 26.x uses G for Quick Actions) |
| Echoes screen (orders, groups, gates) | H |

## Commands
Everyone: `/gatebound status | stat <stat> <n> | awaken | ping | gate leave | echo list|summon|dismiss|release <i>|mode follow|guard | combat debug | tower info`.
Ops: `level set`, `xp add`, `class reset`, `mana set`, `element grant`, `magic odds`, `combat ...`,
`gate spawn <rank e|d|c|b|a|s>`, `gate spawn floor <n>` (gate of that floor's rank), `gate spawn village`,
`tower floor|build|regen|prebuild ...`, `npc spawn broker|scholar`,
`echo add <entity>` (adds a mob to your roster and raises slots to fit), `echo chance` (now unused), `echo debug`.

## Systems and where they live (`common/src/main/java/dev/gatebound/`)
- **data/**: `PlayerData` (level, xp, stat points, stats, class, elements, mana, echo roster), `PlayerDataStore` (vanilla SavedData),
  `Progression`, `Stat`, `GateboundClass` (Warrior, Rogue, Mage, Ranger, Summoner), `Element`.
- **combat/**: server-authoritative parry with posture/stagger, dodge i-frames, stamina, mob wind-up goals (`TelegraphGoal`).
- **skill/**: 22+ skills, mana spent at cast, **no cooldowns by design** (mana is the only limit). `Skills.java` holds the table.
- **config/GateboundConfig**: reflection-based, writes `config/gatebound.json` with sections per config class
  (`CombatConfig`, `MagicConfig`, `EchoConfig`, `TradeConfig`, `DungeonConfig`).
- **client/**: HUD, status screen, awakening and element screens, NPC trade screen.
- **trade/**: custom NPC entity (`GateboundNpc`, a Villager subclass used only for the model), Skill Broker and Scholar shops, items (Gate Shard, tomes, info scroll, respec token, **Echo Shard**).
- **dungeon/**: the Spire tower, gates, gate dungeons, leaking, labels, hall (details below).
- **echo/**: Echoes (details below).

## The Spire (tower)
- One tower, 25 to 60 floors (random per world, fixed afterwards), floors built lazily and deterministically from (seed, floor) through a per-tick work queue.
- Rest floor every 5th, guardian floor every 10th, final boss on the top floor.
- Overworld landmark about 70 blocks east of spawn (`TowerJob`), with the entrance arch. **Guild hall** inside (`TowerHall`): carpet, dais, pillars,
  campfires, lecterns, plus the Skill Broker and Scholar NPCs. It repairs itself and works on old towers.
- **Protection:** players cannot break or place blocks in the tower or in the dungeon dimension, creative included
  (`protectFromCreative` in the dungeon config; set false to build while testing). Explosions and fluids are not covered.
- Death in the Spire: wake at the last rest floor, or at the tower entrance.
- Floating labels (`GateLabels`) above gates and floor portals (armor stands tagged `gatebound_label`). The tower's own entrance gate is deliberately unlabeled.

## Wall portals (Spire floor portals)
- The floor portals (`GateExitBlock`: HOME, UP, DOWN) are now **two-block-tall panes on walls**, facing into the room, so nobody steps on one by accident.
  Properties: `kind`, `facing` (the pane faces into the room), `half` (lower/upper). Only the slice against the wall counts as "inside"; right-click also works.
- `dungeon/PortalPlacer` finds the wall: from the plan's nominal spot (the old floor position) it scans up to 9 blocks, preferring west for DOWN and rift portals and east for UP
  (away from where you arrive), needing a 2-high solid wall with clear space in front; falls back to a free-standing pane. It is deterministic, so `PortalPlacer.where(...)` finds the same spot later
  (used by Echo gate search and the self-test). Boss arena pillars were shifted half a step off the east-west axis so they do not block the exit wall, and the crying-obsidian pedestal was removed.
- **Old floors still have flat pads** (they now decode as single floating panes): run `/gatebound tower regen` or rebuild floors to get wall portals. Needs in-game verification: wall spots in caves, arena exit, rest floors, rifts.
- The tower entrance gate and overworld gates (`GateBlock`) are unchanged: standing panes, as before.

## Gates and gate dungeons (the newest change, untested in play)
- **Gates are separate from the Spire.** Entering a gate takes you to its **own gate dungeon**: rooms and corridors with a boss arena, generated by `FloorPlan`
  as a BOSS-kind plan in its own region (floor indices from `Spire.GATE_BASE = 2000`, slot * 6 + rank). The tower entrance arch still goes to Spire floor 1.
- Difficulty comes from the gate's rank (E to S) via `Spire.scaleFloor`. Boss: "<Rank>-Rank Gate Guardian".
- **Leak:** an open gate spawns rank-scaled monsters next to it (`GateLeak`), faster as it ages, capped near the gate, persistent and sun-proof,
  until the guardian is killed. Killing it closes the gate (`GateRef.cleared`), gives XP, Gate Shards and (rank C+) an Echo Shard.
- Gates no longer expire or use up. Death inside a gate dungeon: wake outside the gate. All portals inside lead back out.
- **Natural gates:** random spots in the overworld, and (half of the time, `villageGateChance`) in the nearest village. `/gatebound gate spawn village` forces one.
- Config (dungeon section): `gateLeaks`, `leakInterval`, `leakMinInterval`, `leakCap`, `maxNaturalGates`, `villageGateChance`, `villageSearchChunks`, pull settings.
- Gravitational pull toward gates (`GatePull`), vortex particles, hum.

## Echo orders (current)
- **Guard** (0): stays within about 5 blocks, fights only what has just hurt the owner or itself (or is targeting them), and drops any fight beyond 12 blocks of the owner.
- **Attack** (1): hunts the nearest enemies within 20 blocks of itself, plus whatever the owner is fighting.
- **Seek** (2): gate search sweep (see Gate search). Echoes now have **step height 1.0** (no jumping needed for slabs, stairs, ledges) and a door-opening goal (they used to be stopped by
  closed doors). A **stuck rescue** (`EchoBrain.blink`): after about 3 s of barely moving, twice in a row, a ground echo slips to the nearest standable spot toward its target; Guide uses it too.
  **Dungeon floors:** Seek walks room to room along the floor plan's waypoints (`EchoBrain.pickWaypoint`: spawn spots, centre, portals), in per-echo claimed sectors (48-block cells) so a group splits up without overlapping, nearest unvisited first within a sector, remembering visited ones per owner and floor; random-ring targets are only used outside the dungeon.
- **Guide** (3): waits beside the owner until a gate is found, announces it, then holds until the owner **right-clicks the echo**; it then paths to the gate by the best route
  (full path if reachable, else best waypoint step with sidesteps; fliers fly straight), waits when the owner is 20+ blocks behind, holds at the gate. Right-click again to stop.
  A Guide echo's right-click is the signal, so a flying echo is only rideable while it is not in a Guide group. Old saved orders shift (old Follow = Guard, old Guard = Attack).

## Echoes (minions): current design
- An Echo is a **custom entity** (`EchoEntity`, type `gatebound:echo`) with fully custom AI (`EchoBrain`). It only borrows the look of the mob it was bound from:
  the mob type is synced data and `EchoRenderer` draws the vanilla model through a hidden client-side template entity (it needs `setId`, zero extra offset, and a visual scale).
  Not saved to disk; stale copies remove themselves; lost echoes (other dimension, unloaded chunks) are respawned next to the owner for free; echoes come back free after the owner respawns.
- **Binding (new flow):** a Summoner's kill leaves a **soul body** (`EchoCorpse`, type `gatebound:soul_body`, lasts `corpseTicks`, default 2 minutes).
  Right-click it to open the bind menu (`EchoBindScreen`) and pick a **group**; the group's order is the role. No random bind chance anymore.
- **Any mob** can be an echo except the Wither and Ender Dragon.
- **Groups and orders:** four groups, each with one order: Guard, Attack, Seek, Guide (see below).
  Defaults: group 1 guards, 2 attacks, 3 seeks, 4 guides. H opens `EchoScreen`: group orders, direct G1 to G4 buttons per echo, bulk buttons (even split, by type, all to group),
  paging, and the found-gate list (select the gate guides lead to). Per-echo order overrides exist only while using Scout's Eye.
- **Gate search:** fan-out sweeps with explored-area memory, reachability checks, stuck detection, 56/80 block sight. Found gates are reported in chat and listed on the H screen.
- **Abilities** (`EchoKits`): archers and casters use instant bolts with particle trails; witch heals the owner below 60% health with potion splashes and heals hurt echoes;
  creeper blasts enemies without breaking blocks; enderman teleports; wither skeleton withers; etc. Anything unlisted is a melee fighter.
- **Flying echoes** (phantom, bat, blaze, ghast, vex, allay, bee, parrot, breeze, happy ghast) really fly (flying navigation) and **can be ridden**:
  right-click one of your own with an empty hand; it flies where you look; sneak to dismount. Flying echoes have a minimum size so they can carry a rider.
- **Scout's Eye** (Summoner skill, level 3, 12 mana): cast it looking at one of your echoes to see through its eyes (`EchoView`, camera switch by payload).
  Your body is frozen and protected (slowness plus resistance). Press H to order it to Scout or Go to gate. Sneak or 90 s ends it.
- **Echo slots:** 2 + Intellect/10 (capped at 12) + **Echo Shards** (up to 18 extra, hard cap 30 total). Echo Shards: sold by the Skill Broker
  (about 30 Gate Shards plus half your level, 2/day), dropped by gate dungeon guardians of rank C+, and given by Spire guardian first clears (1; final boss 3). `/give @s gatebound:echo_shard`.
- Files: `echo/` (EchoManager, EchoEntity, EchoCorpse, EchoBrain, EchoKits, EchoRenderer, EchoScreen, EchoBindScreen, EchoView, EchoRoster, payloads, EchoNetwork, EchoClient, EchoCommands, EchoConfig, EchoEntities).

## Summoner (feature-complete pass, untested in play)
This is the template for the other classes. Skills live in `Skills.java`; Summoner logic is in `echo/EchoSkills.java`.
- **Skill wheel** (`client/SkillWheelScreen`): tap Z casts the selected skill; hold Z opens the wheel (after 150 ms), point, release to select and cast. Esc cancels.
  The wheel closes by **polling** the real key state in `tick()` (not by key events, which repeat while held and made it flicker). HUD shows only the selected skill.
- **Mana:** no quick regen. The pool refills over half a day (12000 ticks, `ManaState.FULL_REGEN_TICKS`), fully on waking from a night's sleep (`isSleepingLongEnough`),
  or with a **Mana Potion** (restores 60% of max, at least 30; sold by both NPCs for about 6 Gate Shards; every gate clear gives 1 + rank/2). Echo summon still costs 8 mana each.
- **Soul body:** lies on its back (renderer, not the vanilla sideways death pose), lasts **30 s** (`EchoConfig.corpseTicks = 600`).
- **Ladder** (level: skill, mana): 1 Spirit Ward 14, 3 Scout's Eye 12, 5 Soul Drain 16, 7 Rally 10, 10 Command Strike 14, 13 Empower 20, 16 Soul Link 18,
  20 Sacrifice 10, 28 Soul Swap 10, 35 Bind Chains 16, 45 Legion Call 40, 60 Soul Storm 36, 80 Ascension 55, 100 Sovereign's Domain 80.
  Mana pool is 20 + 4*Intellect, so the ultimate needs Intellect 15+.
- **Scaling pattern used:** effect strength = base + level coefficient + Intellect coefficient, capped (Empower x1.3 to x2.0, Soul Link 20% to 60% lifesteal, Ascension x1.8 to x2.8,
  Bind Chains 3 s + level/20, Legion Call 15 s + level/5, Soul Storm damage = echo attack * (2 + 0.04*level + 0.02*Int)). Duration/cooldown-free: mana is the only limit.
- **Echo state** on `EchoEntity`: `empowerUntil/empowerMult`, `commandUntil/commandTarget`, `linkUntil/linkFraction`, `invulnUntil`, `tempUntil` (game-time deadlines), synced `ownerId` (for the HUD).
  Spectral copies (Legion Call) use roster slot `EchoManager.TEMP_SLOT = -1`, are not on the roster, fade by `tempUntil`, never respawn.
- **Binding UX:** soul body shows a countdown in its name and a light column; shift-right-click binds straight into group 1. Slot-full already shows a red message in the menu.
  **Echo HUD** (`echo/EchoHud`): your echoes with health bars, top right.
- **Echo kits:** added melee twists (spider slow, slime knockback, silverfish poison, phantom weakness, vindicator/magma cube knockback).
- **Not done:** real projectiles, non-humanoid attack animations, echo tier scaling (stats already copy the bound mob, so a Warden is strong by itself), swap-prompt when slots are full.
- **Decision for the other classes:** every class needs a ladder like this out to level 100 (about 14 skills), with the same scaling pattern, plus its own signature system.

## Rogue (feature-complete pass, untested in play)
Logic in `skill/RogueSkills.java`, tunables in `skill/RogueConfig.java` (section `rogue` in `config/gatebound.json`; string fields are boss blacklists). Hooks: `CombatHandler.onHurt` calls `RogueSkills.onHurt` first.
- **Ladder** (level: skill, mana): 1 Shadow Step 8 (+4 ticks i-frames via `PlayerCombat.grantIFrames`), 3 Throwing Knives 10, 5 Vanish 18, 8 Mark for Death 12, 10 Shadow Meld 16, 11 Venom Blade 14,
  15 Backstab 14, 20 Smoke Bomb 20, 27 Fan of Knives 22, 35 Blade Dance 30, 45 Execute 35, 60 Shadow Dance 45, 80 Thousand Cuts 60, 100 Shadow Possession 80.
- **Mana:** Rogue max mana = 20 + 4*Agility (`RogueConfig.agilityMana`, switch off to restore Intellect for everyone).
- **Shadow:** invisibility plus a tick that clears mob targets. Broken by taking damage or attacking. The first attack out of it is an **Ambush**: x(2.5 + 0.01*lvl), x1.5 more from behind, x1.5 more from inside a meld. Shadow Dance: never breaks, every hit is an Ambush at x2.
- **Marks:** +25% damage from the marker, Glowing. An Ambush on a marked enemy is a guaranteed crit and refunds 6 mana. Passive (Rogue only): crit chance 0.5% per Luck, crit damage 1.5 + 1% per Agility.
- **Melee bonus damage** is applied one tick later as a second hit (damage inside the hurt event would be eaten by invulnerability frames). Skill hits go through `RogueSkills.hit` (guarded by `applying`).
- **Shadow Meld:** look at any living entity (players included) or nothing (own shadow). Each tick the Rogue is placed behind the host. Exits: sneak (after 5 ticks), attack, damage, host dies/leaves, mana out (1/s), cap 12 + lvl/10 s. Dance and Possession make upkeep free. Wither, Ender Dragon, Warden refused (config).
- **Shadow Walk:** while in any Shadow/Meld, holding W (`ServerPlayer.getLastClientInput().forward()`, no packet needed) hops to the best entity in a 25 degree cone (<= 24 blocks, line of sight), else a dark floor spot (light <= 4) found by a ray fan. 4 mana per hop, 8 tick delay, walls stop the hop.
- **Execute:** the mark jumps to the nearest other enemy on a kill (my reading of "resets Mark"). **Blade Dance** returns you to your start point.
- **Shadow Possession:** binds up to 5 enemies in a 35 degree cone (plus marked ones in range): `setNoAi(true)` + Slowness 255; bosses get Slowness/Weakness only. Bound >= 2 s means possessed (they strike neighbours).
  Controls (the plan had conflicting wording, resolved like this): **cast again = pull** (free), **sneak + cast again = leap to nearest bound enemy**, **hold sneak 1 s = end**, **melee a bound enemy = all arms strike**, **melee a possessed enemy = throw it** at the nearest other enemy.
  4 arms auto-strike every 10 ticks for attack*(1 + 0.01*lvl). Arms deflect projectiles aimed at you (2 mana). Kills add 1 s (max +10 s). Duration 10 + lvl/20 s.
  **Safety:** frozen mobs carry the tag `gatebound_shadow_bound`; release on end/quit/death/mob death, on server stopping, and any tagged mob that loads without an owner is unfrozen (`EntityEvent.ADD`).
- **Not done:** Night's Embrace (named in the plan but not in the ladder), `ShadowSyncPayload` / HUD vignette, a client fallback input packet (not needed, the server input record exists), mixed-version testing. Nothing here has been played yet: check Meld jitter (per-tick teleport of the player), Walk feel, and arm/particle visuals.

## Warrior (first pass, compiles, untested in play)
Logic in `skill/WarriorSkills.java`, tunables in `skill/WarriorConfig.java` (section `warrior`). Mana = 20 + 4*Strength (`strengthMana`). Damage = (base + 0.5*Str) * (1 + 0.012*lvl).
- **Ladder** (level: skill, mana): 1 Shockwave 12, 3 Charge 10, 5 War Cry 20, 8 Cleave 14, 10 Iron Skin 16, 13 Leap Slam 18, 16 Rending Strike 16, 20 Bulwark 18,
  27 Whirlwind 24, 35 Second Wind 30, 45 Crushing Blow 35, 60 Berserker Rage 45, 80 Earthbreaker 60, 100 Avatar of War 80.
- **Signature = Rage + Bulwark:** Berserker Rage / Avatar of War heal 15% / 25% of damage dealt (LIVING_HURT in WarriorSkills); Bulwark reflects 30% of melee damage taken. Avatar pulses every second and ends with a slam.
- Charge, Leap Slam and Earthbreaker are tick-polled (landing detected by `onGround`). Bleed and Whirlwind use a small delayed-task list.
- **Not done:** Warrior gear rules (heavy armour/weapon requirement), HUD for Rage, Warrior passives (like the Rogue's crit), real testing of landing detection and stun feel.

## Ranger (first pass, compiles, untested in play)
Logic in `skill/RangerSkills.java`, tunables in `skill/RangerConfig.java` (section `ranger`). Mana = 20 + 4*Agility. Attack skills need a bow or crossbow in the main hand (no bow: refused and mana refunded).
- **Ladder** (level: skill, mana): 1 Eagle Eye 8, 3 Power Shot 8, 5 Piercing Shot 16 (ray), 8 Hunter's Quarry 12, 10 Evasive Roll 10, 13 Poison Arrow 14, 16 Snare Arrow 16, 20 Multishot 18,
  27 Ricochet 22 (ray), 35 Rain of Arrows 30, 45 Headshot 35 (ray), 60 Hunter's Trance 45, 80 Arrow Barrage 60, 100 Apex Predator 80.
- **Real arrows:** skill arrows are `Arrow` entities tagged `gatebound_ranger_arrow`, no pickup, removed after 15 s or 2 s on the ground. They cost no ammo. Seeking arrows (Trance, Apex, Barrage) steer toward the quarry or nearest enemy each tick.
- **Quarry / range:** +30% damage to the quarry and up to +50% with distance. For arrows this is a second hit one tick later (hurt-event damage would hit i-frames); rays apply it directly. The Ranger's own normal bow shots get the range, quarry and +1%/Agility bonus too.
- **Imbue flow:** Power/Piercing/Poison/Snare/Multishot/Rain/Ricochet/Headshot/Barrage only *prime* the next shot (30 s, mana spent on cast). The Ranger's next arrow (`EntityEvent.ADD`) becomes the skill: arrow skills glow and leave a particle trail; Piercing/Ricochet/Headshot/Barrage cancel the arrow and fire instantly. Rain triggers where the imbued arrow lands: 8 waves of 4 arrows from a low ring (radius 8, height 5) all around the spot.
- **Aiming:** Rangers draw faster (`drawExtraTicks`, reflection on `LivingEntity.useItemRemaining`, run on server and client; the pull animation timing is only approximately matched). While drawing, enemies within 24 get Slowness 6, enemy missiles lose half their speed each tick, and the Ranger takes no damage (`bulletTime`). It is bullet-time for everyone watching, since mobs are really slowed; other players are not.
- **Compound Bow** (`item/CompoundBow.java`, id `gatebound:compound_bow`, recipe: bow + 2 iron + 2 string): zero spread, no arrow drop, 15% faster arrows, 1000 durability, takes Unbreaking, Mending and the bow enchantments, repairs with iron ingots. Crouch + right-click anchors the grapple on the block you look at (range 48, 1 durability) and you hang from the line: gravity is switched off while anchored (entity tag `gatebound_hanging`, cleared on release, quit, death and next join) and a grounded player is lifted 0.75 blocks; the rope length is the distance at anchoring, WASD swings you within it; crouch + right-click again reels you in and you keep hanging where you arrive; jump lets go; in the dungeon dimension the hang ends by itself after 15 s (`DUNGEON_HANG_SECONDS`, warning at 5 s left), elsewhere it is permanent; a pull that never arrives is dropped after 5 s. It uses the real crouch state (`isShiftKeyDown`), so it follows whatever key the player bound to crouch. Uses the vanilla bow textures/models for now (needs its own art).
- **Compound arrows** keep their launch speed for 40 ticks (flying straight), then gravity returns; arrows that stop or stick get gravity back at once (`CompoundBow.tickFlights`). This fixed arrows hanging in the air (a gravity-free arrow loses 1% speed per tick and crawls forever).
- **Quiver** (`item/QuiverItem.java`, `gatebound:quiver`, recipe: 5 leather + string): holds 640 arrows (10 stacks), count in item custom data and shown as lore. Right-click packs all plain arrows from the inventory; sneak + right-click takes out a stack of 64. Holding a bow with no arrows in the inventory lends a stack (marked `quiver_loan`), packed back when the bow is put away.
- **3D models:** hand-built element models, generated by a PowerShell script (palette texture `textures/item/palette.png`, 16 colour swatches; models `compound_bow` + `_pulling_0/1/2`, `quiver`). Not seen in game yet: check hand angles, string/arrow placement while pulling, and the quiver tilt.
- **Bow damage for everyone:** every player's arrows get +3% per Strength (`strengthBowBonus`) and the distance bonus (up to +50% at 24 blocks). Quarry and Agility bonuses stay Ranger-only.
- **Aim visuals (client, `client/RangerAimFx`):** while a Ranger draws, the screen goes greyscale (post effect `assets/gatebound/post_effect/focus.json` using vanilla `color_convolve`, switched on through `LocalPlayer.setActivePostEffects`), the FOV option is eased down to 55% and restored after (never left changed), and the vanilla use-item slow-walk is undone by setting ground speed to normal walking pace. Untested: check the greyscale actually renders, that FOV restores after dying/disconnecting mid-draw, and that the server does not rubber-band the faster walk.
- **Quiver with a full inventory:** holding a bow with no arrows turns the quiver into an arrow stack *in its own slot* (tagged `quiver_loan`, the rest of the arrows stored in its `quiver_reserve` tag and fed in as you shoot), so no free slot is needed. Putting the bow away, logging out or dying turns it back (`QuiverItem.restore`). If the last arrow is shot the quiver reappears in that slot with whatever was left.
- **Quiver on the back (`item/QuiverBack`):** a vanilla `item_display` entity rides the player (so all clients render it with no client code), its yaw follows `yBodyRot`, and it exists while the player carries a quiver and is visible. Tagged `gatebound_quiver_display`; stray ones are discarded on load. Untested: the back offset and the quiver's facing (translation in `spawn`), and that riding a player does not cause side effects (dismount on teleport re-attaches within 10 ticks).
- **Quiver on the back (rewritten):** players cannot carry passengers on the server (the log showed `riding=false`), so the floating-entity approaches are gone. Now the server sends the list of quiver carriers (`QuiverSyncPayload`, `data/ClientQuiverData`) and a client render layer (`client/QuiverBackLayer`, attached to the player model's body part like armour) draws the quiver model. It is registered per loader: `fabric/GateboundFabricClient` (`LivingEntityRenderLayerRegistrationCallback`, new `client` entrypoint in fabric.mod.json) and `neoforge/GateboundNeoForgeClient` (`EntityRenderersEvent.AddLayers`). Both modules compile; untested in game: check position (`translate(0, 0.40, 0.36)` + `scale 1.1` in the layer), crouching, and that it shows on other players.
- **Homing Shot (rewritten):** casting raycasts up to 96 blocks (walls block it) for a living entity (mob or player) or a wooden button / target block / bell, and locks onto it; nothing valid = nothing primed and the mana comes back. The imbued arrow steers at the lock from any distance and keeps its launch speed (air drag no longer makes far shots crawl). Unlocked seekers (Trance, Barrage, Apex) also keep their speed now.
- **Grapple line is a vanilla lead:** the server broadcasts every anchored line (`GrappleSyncPayload`: player, anchor, rope length; `data/ClientGrappleData`) and `client/GrappleLeashLayer` (registered next to the quiver layer for both loaders) calls the game's own `submitLeash` from the player's chest to the anchor. The layer undoes the player model's pose (body yaw, flip, 0.9375 scale) to get the entity-space pose the leash renderer expects. It sags while the player is closer than the rope length (`slack`) and runs straight when taut. The server pull is now an elastic spring (0.35 per block of stretch, max 0.7). Untested: check the rope really starts at the chest and ends on the anchor from all angles; if it is offset or rotated, the pose undo in `GrappleLeashLayer.submit` is the place to fix. The old particle line is gone.
- **Ladder change:** Power Shot (L3) was replaced by **Homing Shot** (L3, 10 mana): imbues the next arrow so it curves to the quarry or the nearest enemy; with nothing to chase it drops gravity-free flight after 1 s.
- **Trance** refunds the mana cost of every cast. Cooldowns are not bypassed. The "instant draw" idea was dropped.
- **Not done:** Ranger gear rules, quarry HUD marker, a client-side check of how homing arrows look, and play-testing (arrow damage = base damage * speed, so check the numbers).

## Test checklist for next session (nothing from the last several changes has been played yet)
1. Launch Fabric then NeoForge clients. Controls list, Awakening, Tab status, spending points.
2. **Persistence:** pick a class, wait 2 s, stop the client from the IDE, relaunch: you should keep your class.
3. **Tower:** hall inside (carpet, pillars, two NPCs), entrance arch, try breaking walls in creative (should be blocked). Rest and guardian floors.
4. **Gates:** `/gatebound gate spawn c`. Wait about 20 s: monsters leak out. Enter: separate dungeon, guardian at the end. Kill it: gate closes, rewards. Die inside: wake outside the gate.
   `/gatebound gate spawn village` near a village. Check the labels above gates.
5. **Echoes (as a Summoner):** kill mobs and look for soul bodies, right-click, pick groups. R to summon. H screen: orders, groups, found gates.
   Seek group near a gate, Guide group follows. Witch healing. Flying echo: right-click to ride. Scout's Eye. Echo Shards (`/give`).
   `/gatebound echo add minecraft:phantom` for quick tests. Leave the tower and rejoin: no duplicate echoes.
6. Combat: parry, dodge, mob wind-ups (the likeliest place for double hits and balance problems).

## Known risks and loose ends
- All client visuals and "feel" are unverified: echo renderer (invisible/untextured bugs were fixed once, but variant mobs may still have wrong textures because the template never gets a spawn-time variant),
  riding a flier (rubber-banding, seat height), Scout's Eye camera and body freeze, soul body pose, bind menu, label heights, hall decoration, gate pull feel.
- Ranged echo attacks are instant hits with particles, not real projectiles. Only humanoid echoes play an attack swing.
- Hitboxes are the registered echo box scaled by height, not the mob's exact shape.
- Gate dungeons reuse slots after about 600 gates (fresh state each reuse); slots far out in the dimension use x up to about 23M.
- Leak mobs and gate rules have not been balance-tested. Leaked monsters stay after the gate closes.
- NPC stock resets on server restart. Textures are generated placeholders. No music or tutorial yet.
- `/gatebound echo chance` and the old random bind chance config no longer do anything.
- NeoForge is `26.3.0.39-beta`; expect occasional breakage. The NeoForge client has not been launched since the NPC and echo-entity changes.

## Ideas the user raised that are not done
- Showing the searched area to the player (particles/map) for gate searching.
- Real projectiles for ranged echoes; more mob-specific abilities.
- Alternative group assignment UIs (drag between group columns, multi-select with checkboxes) if the current one is still disliked.
- More ways to get Echo Shards (rare drops from chests or gate bosses).
- Explosion and fluid protection for the tower and dungeon dimension.

## Recent history (newest first, all on master)
- Gates become separate gate dungeons that leak until cleared.
- Natural gates in villages, `gate spawn village`.
- Soul bodies and the bind menu, default group orders.
- Echo screen group assignment rework (direct buttons, bulk assign, paging).
- Gate search rewrite; echo add command; riding flying echoes; Scout's Eye; Echo Shards.
- Echo groups, Guide order, found-gate selection; tower guild hall and protection.
- Echo brain, orders, kits; labels on gates and portals; auto-flush saving; pinned dev username.
- Echo as custom entity with vanilla-model renderer; stale echo cleanup; NPC entity registration fix.

## Portals (reworked)
- Escape rifts are gone. Each Spire floor has exactly one way down (the entrance) and one way up; a gate dungeon has only its entrance as an exit (no portal after the guardian dies).
- Portals are framed doorways (`PortalPlacer.frame`): stone-brick posts, chiseled lintel, a lantern on each post. Existing floors keep the old layout until rebuilt with `/gatebound tower regen <n>`.
- Seek on dungeon floors is frontier exploration: waypoints once explored (stood within 7 blocks, or seen within 15 with a clear line) stay explored, no second sweep; the next target is the cheapest-to-walk unexplored waypoint in the echo's sector, and a finished echo takes the nearest unexplored branch at once. Far waypoints are approached by partial paths; when the floor is done the echoes wait with the owner and report it.

## Select key (C) and the C wheel
- Hold **C** while looking at a soul body of yours: a wheel of the four groups opens (entries show each group's order and size); release on one to bind it there. Looking at one of your echoes: a wheel of Guard, Attack, Seek, Guide, Group order, Select/Deselect (`EchoWheelScreen`, payloads `EchoWheelPayload` / `EchoWheelPickPayload`, `EchoManager.pressSelect` / `wheelPick`). If the key was already released when the wheel opens it waits for a click.
- A selected echo glows gold (`gatebound_echo_sel` team) and has a `>` in the HUD list. Giving an echo an order from the wheel selects it. Personal orders sit between Scout's Eye and the group order in `EchoManager.effectiveMode`, per roster slot, session only, cleared on release. The H screen shows the selected echo's panel too.
- **Rally:** the first gate a seeker finds (`announce`) becomes the rally point (`EchoManager.setRally/rallyPoint`): every Seek echo on that floor/dimension stops searching and walks there and waits; only the finder keeps announcing. Guides then work as before (right-click).
- **Dungeon routing:** `FloorPlan.distancesTo/hop` give a walkable route over the plan's open cells; `EchoBrain.routeInDungeon` walks ~14 blocks at a time along it. Used by Guide, rally and far Seek waypoints, because the pathfinder alone cannot path across a whole floor.

### Compound bow grapple flow (current, supersedes the older grapple wording above)
1. **Crouch** (the player's own crouch key) with the bow and **keep aiming at the same block for 12 ticks** (`AIM_TICKS`): particles mark the spot, then "Anchor set" and the lead appears. The anchor is dropped after 20 s, if the bow is put away, or if the player gets 72 blocks away.
2. **Shoot** (crouch + right-click) with an anchor set: reels the player in (1 durability; gravity off; a grounded player is lifted 0.75 blocks).
3. On arrival the player **hangs there until jump** (inside the dungeon dimension the line gives way after 15 s; elsewhere there is no time limit; death, dimension change and logout also release). Crouch + right-click without an anchor just shows a hint. A plain right-click still draws the bow.

## Mage (in progress)
Element-based ladders, 9 skills each at levels 1/5/10/18/28/40/60/85/100, in `skill/mage/`. `MageCore` holds the shared machinery
(damage `(base+0.5*Int)*(1+0.012*lvl)`, tick-driven bolts, zones, buffs, 100-level forms, temp blocks restored on expiry/server stop).
- Done: Fire (Scorch: burning takes +25% fire damage, mage-ignited enemies explode on death) and Ice (Chill: 3 stacks freeze; Shatter spends frozen).
- Dark element added to `Element` (lang done) but has no skills yet, so a Dark-only Mage has an empty wheel until `DarkSkills` exists.
- Still old 2-skill versions in `Skills.java`: Lightning, Wind, Earth, Light. Next batches: Lightning + Wind, then Earth + Light + Dark.
- Untested in game. Skill wheel with up to 27 skills (3 elements) still to be checked.

### Mage staffs
`item/StaffItems.java`: one staff per element (`<element>_staff`, 7 incl. Dark), 2 melee damage, orb colour per element
(`textures/item/staff_palette.png`, models/items/recipes generated for all 7). A Mage deals melee damage only with a staff or bare
hands (`MageCore.onHurt`, mirrors the Rogue knife rule). Staffs are not yet required to cast and give no spell bonus.

### Mage: all seven elements, strain, spell picks
- All elements now have 9-skill ladders (Fire, Ice, Lightning, Wind, Earth, Light, Dark) in `skill/mage/*Skills.java`. Resurrection
  (Light 85) is a 60 s blessing that turns the next killing blow into half health (cancels death via LIVING_DEATH).
- Strain (`MageCore.strain`, called from `SkillHandler.use`): one cast per second (`MageConfig.castGapTicks`), each cast spends
  15% of max stamina, stamina under 40% slows, under 15% slows harder, a cast at 0 stamina costs health (15% max, never lethal).
- Multi-element Mages (`SpellPicks`): nine spells in total, one per unlock level (1,5,...,100), chosen from the k elements' spells at that level, on
  elements' spells, chosen on `SpellPickScreen` (opens on join/level-up/element grant, `/gatebound spells` reopens). Picks live in
  `PlayerData.picks` (saved + synced). Single-element Mages get everything.
- Untested in game: all new spells, strain feel, picker screen, flight (uses last client input, may jitter), wall/path restore.

## Stonecutter (multi-version)
- Versions: `1.21.1`, `26.1.2`, `26.2`, `26.3` (settings.gradle `shared { versions ... }`). Each module (common/fabric/neoforge) has one node per version, e.g. `:fabric:26.3`.
- **Write code against 26.3** (the active/VCS version; never switch `stonecutter.active` in `<module>/stonecutter.gradle`). Older versions are produced from it:
  - `common/.../compat/Compat.java`, `ClientCompat.java`, `MobTypes.java`: API differences behind `//? if >=26.x {` ... `//?} else {` `/* old */` `//?}` blocks. Use these for anything that is not a pure rename.
  - `gradle/renames.gradle`: regex rewrites applied only when building 1.21.x (names like `Identifier`->`ResourceLocation`, `MobEffects.SLOWNESS`->`MOVEMENT_SLOWDOWN`, `Item.use` wrapper, SavedData factory, gui draw calls, input events). One match per span: rules must not overlap, or only one applies.
  - Whole-class variants (EchoRenderer, QuiverBackLayer, GrappleLeashLayer, the loader client classes) hold the 1.21.1 version in the commented `else` branch of a conditional.
  - `common/legacy/<mc>/resources`: data files for old formats (recipes, dimension type). Copper knife is skipped on 1.21.1 (no copper tier).
- Per-version dependency versions (Architectury API, Fabric API, NeoForge, Java, remap) live in `gradle/versions.gradle`. Shared node setup is `gradle/node.gradle`. Add a version in both places plus settings.gradle.
- Build/run one version: `./gradlew :fabric:26.3:runClient`, `:neoforge:1.21.1:runServer`, `:fabric:26.2:build`. Root `./gradlew build` builds everything (heavy: close the game first, and build one version at a time if memory is tight).
- Run dirs are per version (`fabric/versions/26.3/run`, ...). The old `fabric/run` and `neoforge/run` are no longer used.
- Smoke test: `:<loader>:<ver>:runServer` loads the mod, then stops at the EULA prompt.
- **After adding code, compile every version** (`./gradlew :common:1.21.1:compileJava :common:26.1.2:compileJava :common:26.2:compileJava`). Errors for the active 26.3 node print paths under `common/src`, the others under `common/versions/<v>/build/generated/stonecutter`.
- 1.21.1 differences that have no equivalent are degraded: Ranger focus post effect (also on 26.1/26.2), Rogue/Mage input reads use movement fields, no copper knife.

### Skill visuals (server-side only)
Toolkit in `skill/fx/`: `Palette` (per-element particles/colours/sounds), `Fx` (rings, shockwaves, spirals, pillars, runes, arcs, projectile trails, impacts),
`Props` (block-display props created/updated by commands, tag `gatebound_fx`, swept on join/start/stop; command failures are logged as "Prop command failed"),
`CastFx` (arm swing + rune + orbiting sparks for every Mage cast, `transform` for the level 100 forms). `MageCore.bolt` has a `Trail` overload.
Done: Fire and Ice. Next: Lightning + Wind, then Earth + Light + Dark, then the other classes. Cast animation is not delayed (spell fires at once).
Untested in game: prop NBT/transform syntax (meteor rock, frost spikes, ice shells), particle load.

## Sounds
- `sound/GateboundSounds`: every vanilla sound the mod plays has a gatebound twin (`SoundPaths`, same path) via `GateboundSounds.of(vanilla)`; extra events with no vanilla twin are in `CustomPaths` (`item.*`, `grave.open`, `chest.smash`, `boss.spawn`, `boss.death`, `gate.ambient`, `skill.<id>` for ~20 signature skills, played by `SkillHandler.use` on a connected cast).
- Real audio: `python tools/gen_sounds.py` synthesizes every event (numpy/scipy + ffmpeg) into two mono `.ogg` variants under `assets/gatebound/sounds/<path>/` and points `sounds.json` at them. Edit the recipe for a path in `RECIPES` and rerun, or drop a hand-made .ogg over a file (Minecraft cannot play mp3). Old note, now superseded: entries were pitch variants of vanilla clips (`"type":"event"`), with `subtitles.gatebound.*` lang keys. To give one a real sound, point its entry at an .ogg; no code change. To add a skill sound, add a path to `CustomPaths` plus a sounds.json entry and subtitle.

### Effect models and textures
`tools/gen_fx_assets.ps1` draws the textures (`textures/item/fx/*.png`) and writes the item models/definitions (`models/item/fx`, `items/fx`):
inscribed magic circle per element (`circle_<element>`), shockwave ring per element, `fireball`, `ice_shard`, `ice_spike`, `meteor`.
Replace any png with hand-made art of the same name. They are shown by `Props.sprite` (item_display + `item_model` component on a paper item, no item registered)
and animated by `Circles` (`ground`, `castCircle` upright in front of the caster, `onTarget` upright in front of the enemy facing the caster, `ring`).
Every Mage cast now plays a ground circle and an upright cast circle; Fire/Ice hits show a circle in front of the target. Still particle-only: Lightning, Wind, Earth, Light, Dark (next batches).
Untested in game: item_display model rendering/transparency, circle orientation and spin, the "Prop command failed" log lines.

### Light visuals
Judgement is now ~7 s: ground circle (whole spell) + two descending halos + closing rings (1.5 s), strike with `light_beam` column model (new texture/model from `tools/gen_fx_assets.ps1`),
5 s of pulsing column with spirals/rising motes/rolling shockwaves and damage pulses (strike dmg(12) + 10 pulses of dmg(1.2) = ~1.5x the old 16), then a fade. Other Light spells got circles, pillars, halo and wings (Seraph).

## PLANNED: effects for every class (not built yet)


### Context
Fire, Ice and Light (Mage) now have real effects: inscribed magic circles on the ground and standing in front of the caster and the enemy, textured 3D models for projectiles and props (shown by `Props.sprite` = item_display + `item_model`), shockwave rings, spirals, pillars and per-form auras, all generated by `tools/gen_fx_assets.ps1`. The user approved that look ("perfect") and wants **the same for the other classes**. Still on plain particles: Warrior (14 skills), Rogue (14), Ranger (14), Summoner (14), and the Mage elements Lightning, Wind, Earth and Dark (36 skills). Same constraints as before: server-side effects, mod assets for textures/models, no gameplay changes, nothing left behind in the world. One new exception requested by the user: the Mage level 100 forms must change the player model and fire elements from the body, which needs a client render layer (batch B).

### Shared groundwork (batch A, first, with its own checks)
- **Neutral toolkit.** `Fx`, `Props`, `Circles` and `CastFx` call `MageCore.run/after/sound/flash/playerOf`. Move those generic helpers (ticker list, `after`, `run`, `bolt`, `zone`, `sound`, `flash`, `line`) into a neutral `skill/fx/Tasks` that `MageCore` delegates to, so non-Mage classes do not depend on the Mage package. Warrior and Rogue keep their own gameplay task lists; they only use `Tasks` for visuals.
- **Palette and circles by key.** `Palette.of(Element)` becomes `Palette.of(String key)` (the Element version stays as a wrapper) so classes get their own palette, circle and ring: `warrior` (steel and ember red), `rogue` (shadow violet with venom green), `ranger` (forest green and gold), `summoner` (soul teal and ghost white). `Circles.*` take a key.
- **Cast animation for all classes.** `SkillHandler` calls `CastFx` for every class, class-flavoured: Warrior = overhead weapon swing + red ground sigil; Rogue = quick flick + shadow puff; Ranger = a bow-draw gesture + green sigil in front; Summoner = both arms raised + soul circle upright in front. Skills that are instant utility (Eagle Eye, Scout's Eye) get a lighter version. Level 100 skills keep their own transformation sequence.
- **New shared assets** (generated by the same script, replaceable): class circles and rings; `slash` crescent (several sizes/tints, flat arc model) used by Warrior, Rogue and Wind; `crack` (radiating ground-crack decal); `reticle` (lock-on ring with ticks); `silhouette` (flat glowing humanoid afterimage); `smoke_puff` sphere; `chain` (link strip model); `soul_wisp` (glow sprite model); `arrow_glow` (skill arrow with fletching); `knife_spin` reuses the existing knife item models directly through `item_model`; `bolt` (jagged vertical lightning strip), `tornado` (stacked flared rings), `stone_spike`, `boulder`, `void_sphere` and `curse_mark` for the remaining Mage elements.
- **Ground-follow helpers** in `Circles`: a circle that follows a moving entity (re-posed every few ticks) for auras and marks, plus a "mark" variant that floats above a head.

### Mage ultimate forms (batch B): the player's body becomes the element
The user wants the level 100 forms to change **the player model itself** and to **shoot the element out of the body**. Particles and props around the player are not enough, so this batch adds a small amount of client rendering (the project already has per-loader player render layers: `QuiverBackLayer`, `GrappleLeashLayer`, registered in `GateboundFabricClient` and `GateboundNeoForgeClient`, guarded by `//? if >=26.1` in the Stonecutter style). This is the one deliberate exception to "server-side only"; everything else stays server-side.
- **Sync.** A `FormSyncPayload` (player UUID to element id, resent when it changes and every couple of seconds, same pattern as `QuiverSyncPayload`) fills `ClientFormData`, so every player sees every other player's form.
- **Body layer.** `FormBodyLayer` (a `RenderLayer<AvatarRenderState, PlayerModel>`) re-draws the whole player model with an animated translucent element skin using vanilla's `energySwirl` / `entityTranslucentEmissive` render types (the same technique as the charged creeper and the wither's armour), so it follows every pose and animation exactly, like armour. Textures `form_<element>.png` (scrolling flame/lava, crystalline ice, crackling lightning, streaming wind, cracked stone with glowing seams, golden light, swirling void) come from the generator script. Glowing eyes via an `eyes` layer.
- **Attachments.** Item models drawn on the body like the quiver (wings, halo, crown, horns, shoulder spikes, floating stones, tendrils), also generated: fire wings and horns, ice crown and shoulder spikes, lightning halo, wind wings, rock pauldrons and floating boulders, feathered wings and halo, void tendrils and a black halo.
- **Real size change for Titan Form** with the vanilla `SCALE` attribute (about 1.5x for the 20 s, removed on end, quit and death), so the model is genuinely larger without client code; the other forms get a slight 1.1x.
- **Elements come out of the body (server side).** New `FormEmitters`: while a form is active, every 8 to 10 ticks projectiles launch from the right place on the body (hands, shoulders, chest, back) towards the nearest enemies, using the existing bolt/props/trail code; the damage the form already deals each pulse is now delivered by these projectiles instead of by an invisible aura, so total damage does not change.
  | Form | Body | Emits |
  |---|---|---|
  | Ifrit | molten fire skin, flame horns and wings, burning eyes | fireballs from the palms, flame jets from the shoulders |
  | Frost Sovereign | ice-crystal armour, crown and shoulder spikes | ice-shard volleys from the shoulders, frost mist from the hands |
  | Thunder God | skin crackling with lightning, lightning halo, white eyes | bolts leaping from the chest and hands to enemies |
  | Wind Avatar | streaming wind skin, wings of wind | wind blades from the arms |
  | Titan | cracked stone skin with glowing seams, floating boulders, 1.5x size | boulders thrown from the back, ground slams from the feet |
  | Seraph | golden light skin, feathered wings, halo | beams from the eyes and palms, rays from the wings |
  | Void Lord | void skin, purple eyes, tendrils | void bolts from the palms, tendrils lashing from the back |
- **Start and end.** A short transformation (the model fades into the skin, ring and pillar, a flash) and an unwinding at the end, both timed from the server.
- **Reuse for class ultimates later:** the same layer can wear Avatar of War (molten steel), Shadow Dance (shadow shroud), Apex Predator (predator spirit) and Sovereign's Domain (soul crown) in their own batches.
- **Risk:** this is new rendering code per Minecraft version (26.1.2, 26.2, 26.3); written with `//?` guards like the existing layers. I can only check it compiles, so the first in-game test of the skin and the attachments is the main risk; if the full-body skin misbehaves the fallback is a thinner shell made only of the attachments.

### Warrior (batch C): weight, impact, steel and embers
| Skill | Effect |
|---|---|
| Shockwave | ground crack decal + dust ring + flying debris; textured ring |
| Charge | speed streak with afterimages, dust wake, a slash flash on every enemy hit |
| War Cry | red sigil under feet, three sonic rings, ember pillar, allies get a small glow |
| Cleave | a big crescent slash model sweeps the arc in front, sparks per hit |
| Iron Skin | steel plates orbit the body (props), metallic spark shimmer for the duration |
| Leap Slam | take-off dust ring, trail, landing crack decal + shockwave + debris dome |
| Rending Strike | triple red claw slashes on the target, red dust drips while it bleeds |
| Bulwark | upright shield sigil in front facing outward, pulse ring on every reflect |
| Whirlwind | spinning blade-ring model around the player + wind spiral |
| Second Wind | gold-green circle, rising motes, heal ring |
| Crushing Blow | huge slash, crack decal and heavy shockwave at the target, camera-feel debris |
| Berserker Rage | pulsing red sigil, flame mantle and steam while it lasts |
| Earthbreaker | crack network spreading outward, stone spikes rising, big ring |
| Avatar of War | giant spectral sword plunged beside the player, horns of flame, large circle, ring pulses |

### Rogue (batch D): speed, shadow, afterimages
| Skill | Effect |
|---|---|
| Shadow Step | silhouette afterimage at the start, smoke streak, puff and dagger flash at arrival |
| Throwing Knives | real knife models spinning in flight with glint trails |
| Vanish | smoke-puff sphere expands, silhouette fades out, shadow mist |
| Mark for Death | red reticle ring floating over the target's head until it ends |
| Shadow Meld | shadow pool circle under the feet that sinks, dark mist tendrils |
| Venom Blade | green drip glow along the blade, poison circle where it hits |
| Backstab | double slash arc and a shadow flash behind the target, red dust burst |
| Smoke Bomb | big smoke spheres that linger, sigil under the cloud, purple smoke rising |
| Fan of Knives | ring of knives flying out in 360 degrees with trails, sigil under feet |
| Blade Dance | silhouettes left at every hop, rapid slash arcs, blur ring |
| Execute | reticle locks on, giant slash X and shadow burst |
| Shadow Dance | several silhouettes orbit, dark mantle for the duration |
| Thousand Cuts | a flurry of slash marks all around the target in quick succession |
| Shadow Possession | dark chains to each bound mob, circle under each, large violet sigil |

### Ranger (batch E): precision, nature, arrows
Skill arrows stay real arrow entities (gameplay unchanged); they get glowing trails and an `arrow_glow` model overlay that follows them.
| Skill | Effect |
|---|---|
| Eagle Eye | sweeping flat ring, golden reticle marks on enemies, feather particles |
| Homing Shot | reticle snaps onto the target, curved green trail |
| Piercing Shot | a stretched light beam model along the ray, rings at each pierced enemy |
| Hunter's Quarry | rotating reticle on the quarry for as long as it is marked |
| Evasive Roll | dust roll puff, afterimage, snap-arrow trail |
| Poison Arrow | green swirl trail, toxic circle where it hits, drifting drips |
| Snare Arrow | root circle at the target's feet with thorn vines rising |
| Multishot | cast circle becomes an arrow star, fan of trails |
| Ricochet | beam bounces with ring flashes at every bounce point |
| Rain of Arrows | large ground circle at the target, arrow props falling in waves |
| Headshot | reticle zooms to the head, crit flash circle and beam |
| Hunter's Trance | forest-green ripple ring that radiates time-slow, leaf swirl, eye circle |
| Arrow Barrage | launcher circle in front, trails of arrows homing in |
| Apex Predator | predator eyes glow above the player, big circle, reticle on every quarry |

### Summoner (batch F): souls, chains, summoning circles
Also add a summoning sequence for the R key (circle on the ground, pillar, echo rising from it) and a soul-release when an echo falls.
| Skill | Effect |
|---|---|
| Spirit Ward | teal wisps orbit, soul circle underfoot |
| Scout's Eye | a floating ghost eye prop that flies out with a wispy trail |
| Soul Drain | stream of wisps flows from the target to the caster, circle in front of the target |
| Rally | ripple rings out to every echo, each gets a small circle and a light chain back |
| Command Strike | crosshair circle on the target, beams of light from each echo |
| Empower | circle at each echo and a rising aura for the whole buff |
| Soul Link | glowing arcs between player and echoes with a link sigil |
| Sacrifice | echo dissolves into souls that stream into the caster |
| Soul Swap | circles at both positions, a swirl of souls trading places |
| Bind Chains | chain models shoot out and tighten around the target, sigil underneath |
| Legion Call | large summoning circle, echoes rise from pillars of light |
| Soul Storm | storm of wisps circling the caster, soul bolts to enemies |
| Ascension | growth aura, circle and pillar on the empowered echoes |
| Sovereign's Domain | giant domain circle and dome shell, chains, pulsing rings for the whole duration |

### Remaining Mage elements (batch G)
- **Lightning:** `bolt` model columns for Thunderstrike, Thunder Storm and Lightning Rod; jagged particle arcs and an upright circle in front of every target for Arc Bolt and Chain Lightning; afterimage streaks for Static Step and Storm Surge; crackling aura for Overcharge and Thunder God.
- **Wind:** `slash` crescents for Wind Blades and Gust, `tornado` model for Tornado, rings and streaks for Air Dash, an upright circle shield for Wind Barrier, wing swirls for the flight spells.
- **Earth:** `stone_spike` models erupting for Stone Spike, Tremor, Earthquake and Landslide, `boulder` model rolling, brick-textured wall for Stone Wall, crack decals, shell of stone for Bedrock and Titan Form.
- **Dark:** `void_sphere` for Shadow Bolt and Black Hole (pulls in, then collapses), `curse_mark` floating over cursed enemies, tendrils for Void Grasp, an eclipse disc for Eclipse, void aura for Void Lord, circles in front of targets.

### Implementation pattern (every batch)
1. Add the batch's textures and models to `tools/gen_fx_assets.ps1`, run it, check the pngs.
2. Replace each skill's `sendParticles` visuals with calls to `Fx`, `Circles` and `Props.sprite` (gameplay lines untouched), one class file at a time with targeted edits: `skill/WarriorSkills.java`, `skill/RogueSkills.java`, `skill/RangerSkills.java`, `echo/EchoSkills.java` (+ `EchoView`, `EchoManager` for summon/death), `skill/mage/{Lightning,Wind,Earth,Dark}Skills.java`.
3. Compile `./gradlew :common:compileJava :fabric:compileJava :neoforge:compileJava`, update `HANDOFF.md`, hand over for in-game checks.
Reuse: `Fx.trail/impact/shockwave/pillar/spiral/swirl/arc`, `Circles.ground/castCircle/onTarget/ring`, `Props.sprite/pose`, `Tasks` (formerly `MageCore.run/after/bolt/zone`).

### Constraints and risks
- Particle and prop budget: cap props per cast (about 12 at once) and circles per spell (about 8 targets); long effects redraw particles every 2 to 4 ticks.
- All props are tagged `gatebound_fx` and swept on join, server start and stop; failures are logged as "Prop command failed".
- The model, orientation and transparency rendering has only been checked by the user for the batches already built; adjust the shared models first if anything looks off, before doing four more classes.
- Skill arrows, knives and echoes are real gameplay entities: their visuals are overlays, never replacements, so hit detection does not change.

### Verification
After each batch: compile all three modules; in game, grant the class (`/gatebound class` or reset), `/gatebound level set 100`, cast every skill and check the cast animation, travel, impact and lingering effect, that no props remain afterwards (also after quitting mid-effect and restarting), and watch tick time with several players casting together.
