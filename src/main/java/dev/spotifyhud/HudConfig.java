package dev.spotifyhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** All user settings, persisted to config/spotifyhud.json. */
public final class HudConfig {
    public enum Style { MODERN, MINIMAL, COMPACT }

    public String clientId = "";
    public int redirectPort = 43019;
    public boolean enabled = true;
    public Style style = Style.MODERN;

    public int x = 8, y = 8, width = 250, height = 80;
    public int bgColor = 0x1E2A3F, bgOpacity = 80;
    public int textColor = 0xFFFFFF, subTextColor = 0xB8C0CC, accentColor = 0x1DB954, borderColor = 0x4A5A78;
    public int borderWidth = 1, cornerRadius = 6, coverSize = 56, textScalePercent = 100;

    public boolean textShadow = true, boldTitle = true;
    public boolean showCover = true, showTitle = true, showArtist = true, showProgress = true,
            showTimes = true, showControls = true, showStatus = true, showEqualizer = true, glow = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HudConfig instance;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("spotifyhud.json");
    }

    public static synchronized HudConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static synchronized void load() {
        instance = null;
        try {
            Path p = path();
            if (Files.exists(p)) instance = GSON.fromJson(Files.readString(p), HudConfig.class);
        } catch (Exception e) {
            SpotifyHudMod.LOG.warn("Could not read config, using defaults", e);
        }
        if (instance == null) instance = new HudConfig();
        if (instance.clientId == null) instance.clientId = "";
        if (instance.style == null) instance.style = Style.MODERN;
        instance.redirectPort = Math.max(1024, Math.min(65535, instance.redirectPort));
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(get()));
        } catch (Exception e) {
            SpotifyHudMod.LOG.warn("Could not save config", e);
        }
    }

    /** Applies a preset. Everything stays individually adjustable afterwards. */
    public void applyStyle(Style s) {
        style = s;
        switch (s) {
            case MODERN -> {
                width = 250; height = 80; coverSize = 56; cornerRadius = 6; borderWidth = 1; bgOpacity = 80;
                showCover = showTitle = showArtist = showProgress = showTimes = showControls = showStatus = showEqualizer = true;
            }
            case MINIMAL -> {
                width = 200; height = 46; cornerRadius = 3; borderWidth = 0; bgOpacity = 45;
                showCover = false; showTitle = showArtist = showProgress = true;
                showTimes = showControls = showStatus = showEqualizer = false;
            }
            case COMPACT -> {
                width = 190; height = 38; coverSize = 28; cornerRadius = 4; borderWidth = 1; bgOpacity = 70;
                showCover = showTitle = showArtist = showProgress = true;
                showTimes = showControls = showStatus = showEqualizer = false;
            }
        }
    }
}
