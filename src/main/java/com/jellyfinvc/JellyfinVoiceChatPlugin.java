package com.jellyfinvc;

import com.jellyfinvc.audio.PlaybackManager;
import com.jellyfinvc.commands.MusicCommand;
import com.jellyfinvc.config.PluginConfig;
import com.jellyfinvc.gui.ChatSearchPrompt;
import com.jellyfinvc.gui.GuiListener;
import com.jellyfinvc.jellyfin.JellyfinClient;
import com.jellyfinvc.jellyfin.PlayerJellyfinRegistry;
import org.bukkit.plugin.java.JavaPlugin;

public final class JellyfinVoiceChatPlugin extends JavaPlugin {

    private PluginConfig config;
    private JellyfinClient jellyfinClient;
    private PlayerJellyfinRegistry jellyfinRegistry;
    private SvcHook svcHook;
    private PlaybackManager playbackManager;
    private ChatSearchPrompt chatSearchPrompt;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        jellyfinClient = new JellyfinClient(config.serverUrl(), config.apiKey(), config.userId());
        jellyfinRegistry = new PlayerJellyfinRegistry(this, jellyfinClient);

        if (!config.isConfigured()) {
            getLogger().warning("jellyfin.server-url / jellyfin.api-key are not set in config.yml yet - "
                    + "playback commands will fail until you configure them and run /music reload, "
                    + "unless a player has set up their own personal server with /music myserver.");
        }

        svcHook = new SvcHook();
        svcHook.setPlaybackManagerSupplier(() -> playbackManager);
        svcHook.setOnReady(() -> {
            playbackManager = new PlaybackManager(this, svcHook.api(), config);
            getLogger().info("Connected to Simple Voice Chat.");
        });

        if (!svcHook.register()) {
            getLogger().severe("Simple Voice Chat was not found. Install/enable it before this plugin - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!svcHook.isReady()) {
            getLogger().warning("Registered with Simple Voice Chat, waiting for it to finish initializing...");
        }

        MusicCommand musicCommand = new MusicCommand(this);
        var command = getCommand("music");
        if (command != null) {
            command.setExecutor(musicCommand);
            command.setTabCompleter(musicCommand);
        }

        chatSearchPrompt = new ChatSearchPrompt(this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(chatSearchPrompt, this);

        getLogger().info("JellyfinVoiceChat enabled.");
    }

    @Override
    public void onDisable() {
        if (playbackManager != null) {
            playbackManager.shutdown();
        }
    }

    public PluginConfig pluginConfig() {
        return config;
    }

    public JellyfinClient jellyfinClient() {
        return jellyfinClient;
    }

    public PlayerJellyfinRegistry jellyfinRegistry() {
        return jellyfinRegistry;
    }

    public SvcHook svcHook() {
        return svcHook;
    }

    /** Null until Simple Voice Chat has finished initializing. */
    public PlaybackManager playbackManager() {
        return playbackManager;
    }

    public ChatSearchPrompt chatSearchPrompt() {
        return chatSearchPrompt;
    }

    public void reload() {
        config.reload();
        jellyfinClient = new JellyfinClient(config.serverUrl(), config.apiKey(), config.userId());
        jellyfinRegistry.updateDefaultClient(jellyfinClient);
    }
}
