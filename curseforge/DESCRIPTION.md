# Gatebound

*An RPG overhaul for Minecraft. Awaken, choose your path, and conquer the gates.*

Gatebound turns survival into a progression-driven action RPG. Awaken as one of five classes, level up to 100, spend stat points, learn a ladder of skills, and fight with a timing-based combat system. Ranked gates tear open in the overworld and leak monsters until you break them. Beyond them stands the Spire, a procedural tower of themed floors, rest sanctuaries and guardians.

**Early release.** Gatebound is under active development. Expect rough edges, and please report bugs on the issue tracker.

## Features at a glance

- **Five classes** with their own skill ladders out to level 100: Warrior, Rogue, Ranger, Mage and Summoner.
- **Timing-based combat**: parry, dodge with i-frames, posture, finishers, stamina and telegraphed mob attacks.
- **Levels and stats**: Strength, Vitality, Intellect, Agility and Luck, with points to spend every level.
- **Mana and skills** chosen from a hold-to-open skill wheel, with proficiency that grows the more you use each skill.
- **Ranked gates (E to S)** that pull you in, leak monsters, and hide a dungeon with a guardian at the end.
- **The Spire**: a procedural tower of 25 to 60 floors with rest floors, guardian floors and a final boss.
- **Echoes**: bind the souls of fallen monsters and command them in groups.
- **NPC trading** with Gate Shards, plus ranked loot, mana crystals and custom monsters.
- Works on **Fabric** and **NeoForge**, in singleplayer and on servers.

## Awakening and classes

When you first join, you awaken and choose a class. Every class has about fourteen skills, unlocking from level 1 to a level 100 ultimate.

- **Warrior**: Shockwave, Charge, War Cry, Cleave, Leap Slam, Whirlwind, Earthbreaker and Avatar of War. Berserker Rage and Bulwark turn damage into health and reflected punishment.
- **Rogue**: Shadow Step, Vanish, Mark for Death, Shadow Meld, Backstab, Blade Dance, Thousand Cuts and Shadow Possession. Strike from the shadows for ambush damage, and hide inside another creature.
- **Ranger**: Homing Shot, Piercing Shot, Hunter's Quarry, Multishot, Rain of Arrows, Hunter's Trance, Arrow Barrage and Apex Predator. Draw fast, slow time while aiming, and swing across caves with the grappling Compound Bow.
- **Mage**: seven elements to master: fire, ice, lightning, wind, earth, light and dark. Each has its own spells and a level 100 transformation. Casting strains your stamina, so every spell counts.
- **Summoner**: Spirit Ward, Scout's Eye, Soul Drain, Rally, Soul Link, Legion Call, Soul Storm and Sovereign's Domain. Your power is the Echoes you bring.

## Skills and proficiency

Skills run on mana, which refills slowly over time, fully when you sleep through the night, or instantly with a Mana Potion. Tap the skill key to cast the selected skill, or hold it to open the wheel, point and release.

Every skill has a proficiency rank from 1 to 10. Each successful cast counts as practice. As a skill ranks up it costs less mana, hits harder, lasts longer, and casts faster.

## Combat

- **Parry** an incoming attack with good timing to break your enemy's posture and open them to a finisher.
- **Dodge** for a window of invulnerability. A perfect dodge shortens your skill cooldowns.
- Monsters **telegraph** their heavy attacks, so reading them matters.
- Your stamina and posture decide who wins a trade.

## Gates

Gates open at random in the overworld and in villages. They are ranked **E, D, C, B, A and S**. A gate has a gravitational pull, and the longer it stays open, the more monsters leak out of it, scaled to its rank.

Step inside to find a winding dungeon of rooms and corridors with a guardian waiting in an arena at the end. Kill the guardian to close the gate and earn XP, Gate Shards, Mana Crystals and, from rank C upward, an Echo Shard. After the guardian falls the dungeon begins to collapse. Loot and leave before the countdown ends, or you will not be leaving at all.

When a run is over, the dungeon is wiped back to bedrock, and the next gate gets a fresh one.

## The Spire

A great tower stands near spawn, with a guild hall inside. Beyond its gate lies the Spire, a tower of 25 to 60 floors, built as you reach them and kept for the life of the world.

- Six themed bands that grow harder as you climb: Catacombs, Mossy Caverns, Flooded Halls, Lava Halls, Ashen Caverns and the Void Sanctum.
- A **rest floor** every fifth floor, with healing waters and a safe place to wake after death.
- A **guardian** every tenth floor, and a final boss at the summit.
- Custom monsters for every band, from Crypt Shamblers to the Void Sovereign.
- Smashable chests, wall portals and mana crystals to mine.
**Mana Crystals** grow in the dungeons and are very hard to break, but become easier once their floor's guardian is dead. Craft them into Mana Potions.

## Echoes

When a Summoner defeats a monster, it leaves a soul body behind. Right-click it to bind that creature as an Echo. Nearly any mob can be bound.

- Sort Echoes into four groups, each with an order: **Guard**, **Attack**, **Seek** or **Guide**.
- **Seek** groups search the world and the dungeons for gates. **Guide** groups lead you to the one you pick.
- Echoes keep the abilities of what they were. Witches heal, archers shoot, endermen teleport, and creepers blast their enemies without breaking blocks.
- Flying Echoes can be ridden.
- See through an Echo's eyes with Scout's Eye.
- Expand your roster with Echo Shards.

## Trading and items

- **Gate Shards** are the currency. Spend them with the Skill Broker and the Scholar in the tower hall.
- **Mana Potions**, **Echo Shards**, respec tokens and scrolls are on offer.
- **Compound Bow**: zero spread, no arrow drop, and a grappling hook for swinging.
- **Quiver**: carries ten stacks of arrows and shows on your back.
- **Graves** hold your things when you die.

## Controls

Everything can be rebound under Controls > Gatebound.

- **Tab**: status window (stats and spending points)
- **Z**: tap to cast the selected skill, hold to open the wheel
- **Mouse button 5**: parry
- **Left Alt**: dodge
- **R**: summon or dismiss Echoes
- **H**: Echo management screen

## Commands

All `/gatebound` commands are for operators, except `/gatebound party`, which every player can use to invite, accept, leave, kick and list party members. Operators get commands for levels, classes, mana, elements, tuning and spawning gates. Vanilla `/locate` also finds the Spire and gates.

## Configuration

A config file is created on first launch at `config/gatebound.json` with every tuning value: combat timing, skill numbers, Echo chances, dungeon and gate spawning, drops and more. Edit it and restart to apply.

By default, players cannot break or place blocks in the Spire or in gate dungeons, creative included. This can be turned off in the dungeon section for building and testing.

## Requirements

- Minecraft **26.3**
- **Fabric**: Fabric Loader, Fabric API and Architectury API
- **NeoForge**: Architectury API
- Install on both the client and the server.

## FAQ

**Can I use it in singleplayer?** Yes.

**Does it work with other mods?** It adds its own dimension, mobs and items and should sit alongside most mods. Please report any conflicts.

**Can I change my class?** Yes. Use a respec token or ask an operator to reset you.

**Can I use it in a modpack?** Please ask first. The mod is currently All Rights Reserved.
