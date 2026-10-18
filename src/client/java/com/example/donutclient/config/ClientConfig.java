package com.example.donutclient.config;

import com.example.donutclient.DonutClient;
import com.example.donutclient.module.Module;
import com.example.donutclient.module.ModuleManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists which modules are enabled to <game dir>/config/donutclient.json.
 *
 * Keybinds are NOT stored here — Minecraft already saves those in options.txt
 * because we registered them as real KeyMappings.
 *
 * Format:
 * {
 *   "modules": { "fps": true, "coordinates": true, "keystrokes": false, "zoom": false }
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
                if (modules.has(m.getId())) {
                    m.setEnabled(modules.get(m.getId()).getAsBoolean());
                }
            }
            DonutClient.LOGGER.info("Loaded config from {}", FILE);
        } catch (IOException | RuntimeException e) {
            DonutClient.LOGGER.error("Failed to read config, using defaults", e);
        }
    }

    public static void save() {
        JsonObject modules = new JsonObject();
        for (Module m : ModuleManager.get().all()) {
            modules.addProperty(m.getId(), m.isEnabled());
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
