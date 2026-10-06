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
- The dev client username is pinned to **Player989** (`loom.runs.client.programArgs` in `fabric/build.gradle` and `neoforge/build.gradle`).
  Offline UUIDs come from the name, so a stable name keeps the same player data between runs.
- Progress is flushed to disk about once a second (`PlayerDataStore.initAutoFlush`), so stopping the client from the IDE no longer loses it.
- Minecraft 26.3 is unobfuscated (Mojang names). Verify APIs with `javap` against
  `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf-26.3/...jar` (the 26.x API differs from older tutorials:
  `Identifier` not `ResourceLocation`, `extractRenderState` screens, `hurtServer`, `EntityTypes`, `Blocks.CARPET.red()` colour collections, etc.).

## Gotchas for whoever edits this (including me)
- In the Bash tool, long heredocs containing quotes tend to break ("unexpected EOF"). Write scripts with the Write tool into the scratchpad
  and run them with `python`, or use Edit.
- The python edit scripts used `str.replace` with exact old text; when a file changes, re-read it first.

## Controls (all rebindable under Controls > Gatebound)
| Action | Default |
|---|---|
| Status window (stats, spend points) | Tab |
| Parry | Mouse button 5 |
| Dodge | Left Alt |
| Skill slots 1 to 6 | Z X C V B N |
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

## Echoes (minions): current design
- An Echo is a **custom entity** (`EchoEntity`, type `gatebound:echo`) with fully custom AI (`EchoBrain`). It only borrows the look of the mob it was bound from:
  the mob type is synced data and `EchoRenderer` draws the vanilla model through a hidden client-side template entity (it needs `setId`, zero extra offset, and a visual scale).
  Not saved to disk; stale copies remove themselves; lost echoes (other dimension, unloaded chunks) are respawned next to the owner for free; echoes come back free after the owner respawns.
- **Binding (new flow):** a Summoner's kill leaves a **soul body** (`EchoCorpse`, type `gatebound:soul_body`, lasts `corpseTicks`, default 2 minutes).
  Right-click it to open the bind menu (`EchoBindScreen`) and pick a **group**; the group's order is the role. No random bind chance anymore.
- **Any mob** can be an echo except the Wither and Ender Dragon.
- **Groups and orders:** four groups, each with one order: Follow, Guard, Seek (search for gates), Guide (lead the owner to the selected gate).
  Defaults: group 1 follows, 2 guards, 3 seeks, 4 guides. H opens `EchoScreen`: group orders, direct G1 to G4 buttons per echo, bulk buttons (even split, by type, all to group),
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

## Summoner refinement (in progress, untested)
- New skills in `echo/EchoSkills`: **Rally** (L7, 10 mana: echoes teleport to you, drop fights, Speed II 5 s), **Command Strike** (L10, 14: mark the looked-at enemy,
  echoes in 40 blocks attack it 6 s with x1.5 damage, Glowing on target), **Empower** (L13, 20: echoes x(1.3 + 0.01*Int, max 1.8) damage and Resistance I for 20 s).
  Echo state lives in `EchoEntity` (`empowerUntil/empowerMult/commandUntil/commandTarget`, `damageMultiplier`). Mana is refunded when there is nothing to act on.
- **Open design point:** skill slots are only 6 (Z X C V B N), and the Summoner now has exactly 6 skills at level 13. More skills need a bigger bar or a page switch.
- **Level 100 goal:** all classes need skills and scaling that last to level 100, with the top end (up to 100) the priority. Currently skills stop at level 5 (13 for Summoner).
- Planned next: Soul Link (L16), Sacrifice (L20), Legion Call (L25) moved/rescaled to the 100 range; echo tier scaling; real projectiles; binding UX (body timer, shift-click quick bind, echo HUD).

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
