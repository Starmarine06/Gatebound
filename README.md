# Gatebound

An original isekai-style RPG mod for Minecraft 26.3 (Fabric + NeoForge, built with Architectury API).
Pick a class, level up, parry and dodge, cast magic, conquer dungeon gates, bind allies and trade for upgrades.

## Requirements
- JDK 25 (Temurin). Set `JAVA_HOME` to it, or use the IntelliJ run configs in `.run/`, which set it for you.
  Edit the path in `.run/*.run.xml` if your JDK lives elsewhere.
- Players need Fabric API (Fabric) and Architectury API 22.0.3+ on both loaders.

## Building and testing
| Target | Command |
|---|---|
| Fabric client | `./gradlew :fabric:runClient` |
| Fabric server | `./gradlew :fabric:runServer` |
| NeoForge client | `./gradlew :neoforge:runClient` |
| NeoForge server | `./gradlew :neoforge:runServer` |
| Build both jars | `./gradlew build` |

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Close any running game client before building,
because it locks the common jar. Server run dirs (`fabric/run`, `neoforge/run`) are git-ignored.

## Default controls (all rebindable under Controls > Gatebound)
| Action | Key |
|---|---|
| Status window | Tab |
| Parry | Mouse button 5 |
| Dodge | Left Alt |
| Skill slots 1 to 6 | Z, X, C, V, B, N |
| Summon / dismiss Echoes | G |

## Config
`config/gatebound.json` is created on first launch with every tuning value (combat timing, magic odds, echo chances,
dungeon spawning, shard drops). Edit it and restart to apply. Missing keys fall back to defaults.

## Commands
Everyone: `/gatebound status`, `stat <stat> <points>`, `awaken`, `ping`, `gate leave`, `echo list|summon|dismiss|release|mode`,
`combat debug`.
Ops: `level set`, `xp add`, `class reset`, `mana set`, `element grant <element>`, `magic odds`, `combat window|posture|tune|stamina|telegraph`,
`gate spawn <rank>`, `echo chance`, `npc spawn broker|scholar`.

## Systems
Progression and stats, classes and Mage elements, timed parry with posture and finishers, dodge with i-frames and stamina,
telegraphed and heavy mob attacks, mana and 22 skills, ranked dungeon gates with bosses, Summoner Echoes, and Gate Shard trading.
