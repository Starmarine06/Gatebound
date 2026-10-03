package dev.gatebound.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import dev.gatebound.Gatebound;
import dev.gatebound.combat.CombatConfig;
import dev.gatebound.dungeon.DungeonConfig;
import dev.gatebound.echo.EchoConfig;
import dev.gatebound.skill.MagicConfig;
import dev.gatebound.trade.TradeConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One JSON file, config/gatebound.json, holding the tuning numbers of every system.
 * Each section maps to a config class; every public static non-final numeric/boolean/int-array field is read by name.
 * Missing keys keep their built-in default, and the file is rewritten with all current values so new options appear
 * automatically. Edit the file and restart the game or server to apply changes.
 */
public final class GateboundConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private GateboundConfig() {
    }

    private static Class<?>[] sections() {
        return new Class<?>[]{CombatConfig.class, MagicConfig.class, EchoConfig.class, TradeConfig.class, DungeonConfig.class};
    }

    public static void load() {
        Path file = Platform.getConfigFolder().resolve("gatebound.json");
        JsonObject root = new JsonObject();
        if (Files.exists(file)) {
            try {
                JsonElement parsed = GSON.fromJson(Files.readString(file), JsonElement.class);
                if (parsed != null && parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject();
                }
            } catch (IOException | RuntimeException e) {
                Gatebound.LOGGER.warn("Could not read {}; using defaults for the whole file ({})", file, e.getMessage());
            }
        }

        JsonObject out = new JsonObject();
        for (Class<?> section : sections()) {
            String name = section.getSimpleName().replace("Config", "").toLowerCase();
            JsonObject in = root.has(name) && root.get(name).isJsonObject() ? root.getAsJsonObject(name) : new JsonObject();
            JsonObject written = new JsonObject();
            for (Field field : section.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods) || !Modifier.isPublic(mods)) {
                    continue;
                }
                try {
                    if (in.has(field.getName())) {
                        apply(field, in.get(field.getName()));
                    }
                    written.add(field.getName(), read(field));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Gatebound.LOGGER.warn("Ignoring bad config value {}.{}: {}", name, field.getName(), e.getMessage());
                }
            }
            out.add(name, written);
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(out));
        } catch (IOException e) {
            Gatebound.LOGGER.warn("Could not write {}: {}", file, e.getMessage());
        }
    }

    private static void apply(Field field, JsonElement value) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == int.class) {
            field.setInt(null, value.getAsInt());
        } else if (type == long.class) {
            field.setLong(null, value.getAsLong());
        } else if (type == double.class) {
            field.setDouble(null, value.getAsDouble());
        } else if (type == boolean.class) {
            field.setBoolean(null, value.getAsBoolean());
        } else if (type == int[].class) {
            JsonArray arr = value.getAsJsonArray();
            int[] current = (int[]) field.get(null);
            int[] next = new int[current.length];
            for (int i = 0; i < next.length; i++) {
                next[i] = i < arr.size() ? arr.get(i).getAsInt() : current[i];
            }
            field.set(null, next);
        }
    }

    private static JsonElement read(Field field) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == int.class) {
            return GSON.toJsonTree(field.getInt(null));
        } else if (type == long.class) {
            return GSON.toJsonTree(field.getLong(null));
        } else if (type == double.class) {
            return GSON.toJsonTree(field.getDouble(null));
        } else if (type == boolean.class) {
            return GSON.toJsonTree(field.getBoolean(null));
        } else if (type == int[].class) {
            return GSON.toJsonTree(field.get(null));
        }
        throw new IllegalArgumentException("unsupported type " + type.getSimpleName());
    }
}
