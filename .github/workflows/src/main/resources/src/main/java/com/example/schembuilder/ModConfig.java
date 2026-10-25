package com.example.schembuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public int ticksPerBlock = 4;

    public static File getConfigFile() {
        File configDir = new File(MinecraftClient.getInstance().runDirectory, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "schembuilder.json");
    }

    public static ModConfig load() {
        File file = getConfigFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                ModConfig cfg = GSON.fromJson(reader, ModConfig.class);
                if (cfg != null && cfg.ticksPerBlock >= 1) {
                    return cfg;
                }
            } catch (Exception ignored) {
            }
        }
        ModConfig def = new ModConfig();
        def.save();
        return def;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(getConfigFile())) {
            GSON.toJson(this, writer);
        } catch (Exception ignored) {
        }
    }
}
