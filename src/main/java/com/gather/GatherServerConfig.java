package com.gather;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class GatherServerConfig {

    private static final Gson GSON = new Gson();
    private static boolean xrayAllowed = false;
    private static Path configPath = null;

    private static class Data {
        boolean allow_xray = false;
    }

    public static boolean isXrayAllowed() { return xrayAllowed; }

    public static void load(MinecraftServer server) {
        configPath = server.getWorldPath(LevelResource.ROOT).resolve("gather_server.json");
        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                Data d = GSON.fromJson(r, Data.class);
                xrayAllowed = d != null && d.allow_xray;
            } catch (Exception e) {
                xrayAllowed = false;
            }
        } else {
            xrayAllowed = false;
            save();
        }
    }

    public static boolean toggleXray() {
        xrayAllowed = !xrayAllowed;
        save();
        return xrayAllowed;
    }

    public static void setXray(boolean value) {
        xrayAllowed = value;
        save();
    }

    private static void save() {
        if (configPath == null) return;
        try (Writer w = Files.newBufferedWriter(configPath)) {
            Data d = new Data();
            d.allow_xray = xrayAllowed;
            GSON.toJson(d, w);
        } catch (Exception ignored) {}
    }
}
