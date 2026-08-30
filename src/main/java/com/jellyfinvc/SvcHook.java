package com.jellyfinvc;

import com.jellyfinvc.audio.PlaybackManager;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;

import java.util.function.Supplier;

/**
 * Registers this plugin with Simple Voice Chat, exposes the resulting
 * {@link VoicechatServerApi} once SVC calls back into {@link #initialize},
 * and keeps group/broadcast/personal audio channel membership in sync with
 * real-time connection and group-membership changes.
 */
public final class SvcHook implements VoicechatPlugin {

    public static final String PLUGIN_ID = "jellyfin-voicechat";

    private volatile VoicechatServerApi api;
    private Runnable onReady;
    private Supplier<PlaybackManager> playbackManagerSupplier;

    public void setOnReady(Runnable onReady) {
        this.onReady = onReady;
    }

    /** The manager isn't constructed until {@link #initialize} runs, so this is looked up lazily. */
    public void setPlaybackManagerSupplier(Supplier<PlaybackManager> supplier) {
        this.playbackManagerSupplier = supplier;
    }

    public boolean register() {
        ServicesManager services = Bukkit.getServicesManager();
        BukkitVoicechatService service = services.load(BukkitVoicechatService.class);
        if (service == null) {
            return false;
        }
        service.registerPlugin(this);
        return true;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            this.api = serverApi;
            if (onReady != null) {
                onReady.run();
            }
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        // A player's own audio (personal session, broadcast if not opted out,
        // their current group's session) needs a fresh VoicechatConnection
        // registered as a target whenever they connect - a stale connection
        // from before a reconnect silently stops receiving audio.
        registration.registerEvent(PlayerConnectedEvent.class, event -> {
            PlaybackManager manager = manager();
            if (manager != null) {
                manager.refreshTargetsForConnection(event.getConnection());
            }
        });
        // Joining/leaving a voice chat group should immediately start/stop
        // hearing that group's music, without needing to reconnect.
        registration.registerEvent(JoinGroupEvent.class, event -> {
            PlaybackManager manager = manager();
            if (manager != null) {
                manager.refreshTargetsForConnection(event.getConnection());
            }
        });
        registration.registerEvent(LeaveGroupEvent.class, event -> {
            PlaybackManager manager = manager();
            if (manager != null && event.getGroup() != null) {
                manager.removeFromGroupSession(event.getConnection(), event.getGroup().getId());
            }
        });
    }

    private PlaybackManager manager() {
        return playbackManagerSupplier == null ? null : playbackManagerSupplier.get();
    }

    public VoicechatServerApi api() {
        return api;
    }

    public boolean isReady() {
        return api != null;
    }
}
