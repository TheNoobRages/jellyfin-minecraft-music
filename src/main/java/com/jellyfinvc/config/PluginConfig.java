package com.jellyfinvc.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginConfig {

    private final JavaPlugin plugin;

    private String serverUrl;
    private String apiKey;
    private String userId;
    private int searchLimit;
    private float defaultVolume;
    private float broadcastDefaultVolume;
    private float jukeboxDistance;
    private int bufferFrames;
    private boolean broadcastEnabled;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.serverUrl = stripTrailingSlash(cfg.getString("jellyfin.server-url", "http://localhost:8096"));
        this.apiKey = cfg.getString("jellyfin.api-key", "");
        this.userId = cfg.getString("jellyfin.user-id", "");
        this.searchLimit = cfg.getInt("jellyfin.search-limit", 20);
        this.defaultVolume = (float) cfg.getDouble("playback.default-volume", 0.5);
        this.broadcastDefaultVolume = (float) cfg.getDouble("playback.broadcast-default-volume", 0.2);
        this.jukeboxDistance = (float) cfg.getDouble("playback.jukebox-distance", 48.0);
        this.bufferFrames = cfg.getInt("playback.buffer-frames", 50);
        this.broadcastEnabled = cfg.getBoolean("playback.broadcast-enabled", true);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String userId() {
        return userId;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && serverUrl != null && !serverUrl.isBlank();
    }

    public int searchLimit() {
        return searchLimit;
    }

    public float defaultVolume() {
        return defaultVolume;
    }

    public float broadcastDefaultVolume() {
        return broadcastDefaultVolume;
    }

    public float jukeboxDistance() {
        return jukeboxDistance;
    }

    public int bufferFrames() {
        return bufferFrames;
    }

    public boolean broadcastEnabled() {
        return broadcastEnabled;
    }

    /** Persists the toggle to config.yml so it survives a restart. */
    public void setBroadcastEnabled(boolean enabled) {
        this.broadcastEnabled = enabled;
        plugin.getConfig().set("playback.broadcast-enabled", enabled);
        plugin.saveConfig();
    }
}
