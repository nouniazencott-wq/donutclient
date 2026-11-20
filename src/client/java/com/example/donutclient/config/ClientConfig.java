package com.example.donutclient.config;

import com.example.donutclient.DonutClient;
import com.example.donutclient.module.Module;
import com.example.donutclient.module.ModuleManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.example.donutclient.setting.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists module state and settings to <game dir>/config/donutclient.json.
 *
 * Keybinds are NOT stored here — Minecraft already saves those in options.txt
 * because we registered them as real KeyMappings.
 *
 * Format:
 * {
 *   "modules": { "base_finder": { "enabled": true, "settings": { "min_score": 12.0 } }, ... }
 * }
 */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir().resolve(DonutClient.MOD_ID + ".json");

    private ClientConfig() {}

    public static void load() {
        if (!Files.exists(FILE)) {
            save(); // write defaults so users can see the file exists
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            for (Module m : ModuleManager.get().all()) {
                if (!modules.has(m.getId())) continue;
                JsonElement entry = modules.get(m.getId());
                if (entry.isJsonPrimitive()) { // 0.1 format: just the enabled flag
                    m.setEnabled(entry.getAsBoolean());
                    continue;
                }
                JsonObject obj = entry.getAsJsonObject();
                JsonObject settings = obj.has("settings") ? obj.getAsJsonObject("settings") : new JsonObject();
                for (Setting s : m.getSettings()) {
                    if (settings.has(s.getId())) s.fromJson(settings.get(s.getId()));
                }
                if (obj.has("enabled")) m.setEnabled(obj.get("enabled").getAsBoolean());
            }
            DonutClient.LOGGER.info("Loaded config from {}", FILE);
        } catch (IOException | RuntimeException e) {
            DonutClient.LOGGER.error("Failed to read config, using defaults", e);
        }
    }

    public static void save() {
        JsonObject modules = new JsonObject();
        for (Module m : ModuleManager.get().all()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", m.isEnabled());
            JsonObject settings = new JsonObject();
            for (Setting s : m.getSettings()) settings.add(s.getId(), s.toJson());
            obj.add("settings", settings);
            modules.add(m.getId(), obj);
        }
        JsonObject root = new JsonObject();
        root.add("modules", modules);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException e) {
            DonutClient.LOGGER.error("Failed to write config", e);
        }
    }
}
