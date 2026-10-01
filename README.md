# Gatebound

Isekai-style RPG mod for Minecraft 26.3 (Fabric + NeoForge, Architectury API).

## Requirements
- JDK 25 (Temurin). Set `JAVA_HOME` to it, or use the IntelliJ run configs in `.run/`, which set it for you.
  Edit the path in `.run/*.run.xml` if your JDK lives elsewhere.

## Test commands
| Target | Command |
|---|---|
| Fabric client | `./gradlew :fabric:runClient` |
| Fabric server | `./gradlew :fabric:runServer` |
| NeoForge client | `./gradlew :neoforge:runClient` |
| NeoForge server | `./gradlew :neoforge:runServer` |
| Build both jars | `./gradlew build` |

Jars land in `fabric/build/libs` and `neoforge/build/libs`.
Server run dirs (`fabric/run`, `neoforge/run`) are git-ignored; EULA and offline mode are pre-set locally.
