package com.jellyfinvc.jellyfin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lets each player optionally point this plugin at their own Jellyfin server
 * instead of the one the server operator configured in config.yml. Players
 * with nothing configured transparently fall back to that shared default -
 * it stays "the main one everyone can use."
 *
 * <p>Credentials are stored per-player in plugins/JellyfinVoiceChat/player-servers.yml,
 * in plaintext (same as the shared config.yml's API key) - keep that file
 * private the same way.
 */
public final class PlayerJellyfinRegistry {

    private final JavaPlugin plugin;
    private final File file;
    private volatile JellyfinClient defaultClient;

    private final Map<UUID, PersonalJellyfinConfig> configs = new ConcurrentHashMap<>();
    private final Map<UUID, JellyfinClient> clientCache = new ConcurrentHashMap<>();

    public PlayerJellyfinRegistry(JavaPlugin plugin, JellyfinClient defaultClient) {
        this.plugin = plugin;
        this.defaultClient = defaultClient;
        this.file = new File(plugin.getDataFolder(), "player-servers.yml");
        load();
    }

    /** The player's own server if they've set one and it's usable, otherwise the shared default. */
    public JellyfinClient clientFor(Player player) {
        UUID uuid = player.getUniqueId();
        PersonalJellyfinConfig cfg = configs.get(uuid);
        if (cfg == null) {
            return defaultClient;
        }
        return clientCache.computeIfAbsent(uuid, u -> new JellyfinClient(cfg.serverUrl(), cfg.apiKey(), cfg.userId()));
    }

    public JellyfinClient defaultClient() {
        return defaultClient;
    }

    /** Called after /music reload picks up new shared server-url/api-key values. */
    public void updateDefaultClient(JellyfinClient defaultClient) {
        this.defaultClient = defaultClient;
    }

    public boolean hasPersonalServer(Player player) {
        return configs.containsKey(player.getUniqueId());
    }

    /** Null if the player has no personal server configured. */
    public String personalServerUrl(Player player) {
        PersonalJellyfinConfig cfg = configs.get(player.getUniqueId());
        return cfg == null ? null : cfg.serverUrl();
    }

    public void setPersonalServer(Player player, String serverUrl, String apiKey, String userId) {
        UUID uuid = player.getUniqueId();
        PersonalJellyfinConfig cfg = new PersonalJellyfinConfig(
                JellyfinClient.stripTrailingSlash(serverUrl), apiKey, userId == null ? "" : userId);
        configs.put(uuid, cfg);
        clientCache.remove(uuid);
        save();
    }

    public void clearPersonalServer(Player player) {
        UUID uuid = player.getUniqueId();
        configs.remove(uuid);
        clientCache.remove(uuid);
        save();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String url = yaml.getString(key + ".server-url", "");
            String apiKey = yaml.getString(key + ".api-key", "");
            String userId = yaml.getString(key + ".user-id", "");
            if (!url.isBlank() && !apiKey.isBlank()) {
                configs.put(uuid, new PersonalJellyfinConfig(url, apiKey, userId));
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PersonalJellyfinConfig> entry : configs.entrySet()) {
            String base = entry.getKey().toString();
            PersonalJellyfinConfig cfg = entry.getValue();
            yaml.set(base + ".server-url", cfg.serverUrl());
            yaml.set(base + ".api-key", cfg.apiKey());
            yaml.set(base + ".user-id", cfg.userId());
        }
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save player-servers.yml", e);
        }
    }
}
