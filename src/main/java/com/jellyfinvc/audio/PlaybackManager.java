package com.jellyfinvc.audio;

import com.jellyfinvc.config.PluginConfig;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every active {@link PlaybackSession}, keyed by scope: one per player
 * for personal streams, one per SVC group, one per placed jukebox, and a
 * single shared one for server-wide broadcast.
 */
public final class PlaybackManager {

    private final JavaPlugin plugin;
    private final VoicechatServerApi api;
    private final PluginConfig config;

    private final Map<String, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, JukeboxInfo> jukeboxes = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> broadcastOptedOut = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> mutedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> albumRadioDisabled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PlaybackManager(JavaPlugin plugin, VoicechatServerApi api, PluginConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    public PlaybackSession get(String key) {
        return sessions.get(key);
    }

    public static String personalKey(Player player) {
        return "personal:" + player.getUniqueId();
    }

    public static String groupKey(UUID groupId) {
        return "group:" + groupId;
    }

    public static String broadcastKey() {
        return "broadcast";
    }

    public PlaybackSession personalSession(Player owner) {
        String key = personalKey(owner);
        return sessions.computeIfAbsent(key, k -> {
            UUID channelId = UUID.randomUUID();
            StaticAudioChannel channel = api.createStaticAudioChannel(channelId);
            UUID ownerUuid = owner.getUniqueId();
            channel.setFilter(sp -> sp.getUuid().equals(ownerUuid) && !mutedPlayers.contains(ownerUuid));
            // Belt-and-suspenders: StaticAudioChannel also exposes an explicit
            // target list (addTarget/removeTarget/clearTargets). setFilter is
            // the documented recipient mechanism, but adding the owner as an
            // explicit target too costs nothing and rules out any dependency
            // on the target list being non-empty.
            VoicechatConnection ownerConnection = api.getConnectionOf(ownerUuid);
            if (ownerConnection != null) {
                channel.addTarget(ownerConnection);
            }
            return newSession(k, channel);
        });
    }

    /** Returns null if the player isn't currently in an SVC group. */
    public PlaybackSession groupSessionFor(Player member) {
        VoicechatConnection connection = api.getConnectionOf(member.getUniqueId());
        if (connection == null) {
            return null;
        }
        Group group = connection.getGroup();
        if (group == null) {
            return null;
        }
        UUID groupId = group.getId();
        String key = groupKey(groupId);
        return sessions.computeIfAbsent(key, k -> {
            UUID channelId = UUID.randomUUID();
            StaticAudioChannel channel = api.createStaticAudioChannel(channelId);
            channel.setFilter(sp -> {
                if (mutedPlayers.contains(sp.getUuid())) {
                    return false;
                }
                VoicechatConnection conn = api.getConnectionOf(sp.getUuid());
                if (conn == null) {
                    return false;
                }
                Group g = conn.getGroup();
                return g != null && g.getId().equals(groupId);
            });
            for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
                VoicechatConnection conn = api.getConnectionOf(online.getUniqueId());
                if (conn != null) {
                    Group g = conn.getGroup();
                    if (g != null && g.getId().equals(groupId)) {
                        channel.addTarget(conn);
                    }
                }
            }
            return newSession(k, channel);
        });
    }

    /** Null if broadcast has been disabled server-wide (see {@link #setBroadcastEnabled}). */
    public PlaybackSession broadcastSession() {
        if (!config.broadcastEnabled()) {
            return null;
        }
        String key = broadcastKey();
        return sessions.computeIfAbsent(key, k -> {
            UUID channelId = UUID.randomUUID();
            StaticAudioChannel channel = api.createStaticAudioChannel(channelId);
            channel.setFilter(sp -> !broadcastOptedOut.contains(sp.getUuid()) && !mutedPlayers.contains(sp.getUuid()));
            for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
                if (broadcastOptedOut.contains(online.getUniqueId()) || mutedPlayers.contains(online.getUniqueId())) {
                    continue;
                }
                VoicechatConnection conn = api.getConnectionOf(online.getUniqueId());
                if (conn != null) {
                    channel.addTarget(conn);
                }
            }
            return newSession(k, channel, null, config.broadcastDefaultVolume());
        });
    }

    /**
     * Admin kill switch for the whole broadcast feature, distinct from
     * {@link #setBroadcastOptOut} (which is a per-player choice to not hear
     * it). Disabling stops any broadcast currently playing immediately and
     * blocks starting a new one, until re-enabled. Persisted to config.yml.
     */
    public void setBroadcastEnabled(boolean enabled) {
        config.setBroadcastEnabled(enabled);
        if (!enabled) {
            PlaybackSession broadcast = sessions.get(broadcastKey());
            if (broadcast != null) {
                broadcast.stopAll();
            }
        }
    }

    public boolean isBroadcastEnabled() {
        return config.broadcastEnabled();
    }

    /** Lets a player opt in/out of hearing server-wide broadcasts. */
    public void setBroadcastOptOut(Player player, boolean optOut) {
        UUID uuid = player.getUniqueId();
        if (optOut) {
            broadcastOptedOut.add(uuid);
        } else {
            broadcastOptedOut.remove(uuid);
        }
        PlaybackSession broadcast = sessions.get(broadcastKey());
        if (broadcast == null) {
            return;
        }
        VoicechatConnection connection = api.getConnectionOf(uuid);
        if (connection == null) {
            return;
        }
        if (optOut) {
            broadcast.removeTarget(connection);
        } else {
            broadcast.ensureTarget(connection);
        }
    }

    public boolean isBroadcastOptedOut(Player player) {
        return broadcastOptedOut.contains(player.getUniqueId());
    }

    /**
     * Master mute: stops a player hearing this plugin's audio entirely -
     * personal, group, broadcast, and any jukebox - independent of their
     * per-scope settings (like a broadcast opt-out), which take effect again
     * once unmuted. Jukebox channels have no target list to update, only a
     * filter, so muting them takes effect purely through the filter checked
     * at creation time; personal/group/broadcast also get their explicit
     * target list updated immediately, the same as the broadcast opt-out.
     */
    public void setMuted(Player player, boolean muted) {
        UUID uuid = player.getUniqueId();
        if (muted) {
            mutedPlayers.add(uuid);
        } else {
            mutedPlayers.remove(uuid);
        }
        VoicechatConnection connection = api.getConnectionOf(uuid);
        if (connection == null) {
            return;
        }
        PlaybackSession personal = sessions.get(personalKey(player));
        if (personal != null) {
            if (muted) {
                personal.removeTarget(connection);
            } else {
                personal.ensureTarget(connection);
            }
        }
        PlaybackSession broadcast = sessions.get(broadcastKey());
        if (broadcast != null) {
            if (muted) {
                broadcast.removeTarget(connection);
            } else if (!broadcastOptedOut.contains(uuid)) {
                broadcast.ensureTarget(connection);
            }
        }
        Group group = connection.getGroup();
        if (group != null) {
            PlaybackSession groupSession = sessions.get(groupKey(group.getId()));
            if (groupSession != null) {
                if (muted) {
                    groupSession.removeTarget(connection);
                } else {
                    groupSession.ensureTarget(connection);
                }
            }
        }
    }

    public boolean isMuted(Player player) {
        return mutedPlayers.contains(player.getUniqueId());
    }

    /**
     * Whether playing a whole album for this player should automatically
     * continue into another random album once it ends. Defaults to on;
     * players can turn it off for themselves.
     */
    public boolean isAlbumRadioEnabled(Player player) {
        return !albumRadioDisabled.contains(player.getUniqueId());
    }

    public void setAlbumRadioEnabled(Player player, boolean enabled) {
        if (enabled) {
            albumRadioDisabled.remove(player.getUniqueId());
        } else {
            albumRadioDisabled.add(player.getUniqueId());
        }
    }

    public JukeboxInfo placeJukebox(Player owner, Location location) {
        UUID jukeboxId = UUID.randomUUID();
        JukeboxInfo info = new JukeboxInfo(jukeboxId, owner.getUniqueId(), owner.getName(), location);
        jukeboxes.put(jukeboxId, info);

        UUID channelId = UUID.randomUUID();
        LocationalAudioChannel channel = api.createLocationalAudioChannel(
                channelId,
                api.fromServerLevel(location.getWorld()),
                api.createPosition(location.getX(), location.getY(), location.getZ()));
        channel.setDistance(config.jukeboxDistance());
        channel.setFilter(sp -> !mutedPlayers.contains(sp.getUuid()));

        PlaybackSession session = newSession(info.sessionKey(), channel, () -> jukeboxes.remove(jukeboxId));
        sessions.put(info.sessionKey(), session);
        return info;
    }

    public JukeboxInfo nearestJukebox(Location location, double maxDistance) {
        JukeboxInfo nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (JukeboxInfo info : jukeboxes.values()) {
            if (!info.location().getWorld().equals(location.getWorld())) {
                continue;
            }
            double dist = info.location().distance(location);
            if (dist <= maxDistance && dist < nearestDist) {
                nearest = info;
                nearestDist = dist;
            }
        }
        return nearest;
    }

    public List<JukeboxInfo> jukeboxesOwnedBy(UUID ownerUuid) {
        List<JukeboxInfo> result = new ArrayList<>();
        for (JukeboxInfo info : jukeboxes.values()) {
            if (info.ownerUuid().equals(ownerUuid)) {
                result.add(info);
            }
        }
        return result;
    }

    public void stopJukebox(UUID jukeboxId) {
        JukeboxInfo info = jukeboxes.get(jukeboxId);
        if (info == null) {
            return;
        }
        PlaybackSession session = sessions.get(info.sessionKey());
        if (session != null) {
            session.stopAll();
        } else {
            jukeboxes.remove(jukeboxId);
        }
    }

    /**
     * Re-registers a player's fresh voice chat connection as a target on any
     * active session that should reach them: their own personal session, the
     * server-wide broadcast (unless opted out), and their current group's
     * session. Called whenever a player connects/reconnects to voice chat or
     * joins a group, since a stale connection from before a reconnect - or
     * simply never having been added because they just joined the group -
     * silently receives no audio.
     */
    public void refreshTargetsForConnection(VoicechatConnection connection) {
        if (connection == null) {
            return;
        }
        UUID uuid = connection.getPlayer().getUuid();
        if (mutedPlayers.contains(uuid)) {
            return;
        }
        PlaybackSession personal = sessions.get("personal:" + uuid);
        if (personal != null) {
            personal.ensureTarget(connection);
        }
        PlaybackSession broadcast = sessions.get(broadcastKey());
        if (broadcast != null && !broadcastOptedOut.contains(uuid)) {
            broadcast.ensureTarget(connection);
        }
        Group group = connection.getGroup();
        if (group != null) {
            PlaybackSession groupSession = sessions.get(groupKey(group.getId()));
            if (groupSession != null) {
                groupSession.ensureTarget(connection);
            }
        }
    }

    /** Stops a connection from hearing a specific group's session, e.g. right after they leave it. */
    public void removeFromGroupSession(VoicechatConnection connection, UUID groupId) {
        if (connection == null || groupId == null) {
            return;
        }
        PlaybackSession groupSession = sessions.get(groupKey(groupId));
        if (groupSession != null) {
            groupSession.removeTarget(connection);
        }
    }

    public void stopAllForPlayer(Player player) {
        PlaybackSession session = sessions.get(personalKey(player));
        if (session != null) {
            session.stopAll();
        }
    }

    public void shutdown() {
        for (PlaybackSession session : sessions.values()) {
            session.stopAll();
        }
        sessions.clear();
        jukeboxes.clear();
    }

    private PlaybackSession newSession(String key, AudioChannel channel) {
        return newSession(key, channel, null, config.defaultVolume());
    }

    private PlaybackSession newSession(String key, AudioChannel channel, Runnable extraOnEnded) {
        return newSession(key, channel, extraOnEnded, config.defaultVolume());
    }

    private PlaybackSession newSession(String key, AudioChannel channel, Runnable extraOnEnded, float initialVolume) {
        return new PlaybackSession(key, plugin, api, channel, config.bufferFrames(), initialVolume, () -> {
            sessions.remove(key);
            if (extraOnEnded != null) {
                extraOnEnded.run();
            }
        });
    }
}
