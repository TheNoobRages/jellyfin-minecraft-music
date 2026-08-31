package com.jellyfinvc.audio;

import com.jellyfinvc.jellyfin.JellyfinAlbum;
import com.jellyfinvc.jellyfin.JellyfinClient;
import com.jellyfinvc.jellyfin.JellyfinTrack;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * One continuous playback context: a single {@link AudioChannel} (already
 * scoped to whoever should hear it - a jukebox location, one player, an SVC
 * group, or everyone) plus a queue of Jellyfin tracks played one after another.
 *
 * <p>All state here is only ever mutated on the Bukkit main thread. Every
 * entry point that Simple Voice Chat's audio subsystem might invoke off-thread
 * ({@code onFinished}, {@code onStopped}) hops back onto the main thread
 * before touching anything, so nothing here needs its own locking.
 */
public final class PlaybackSession {

    private final String key;
    private final JavaPlugin plugin;
    private final VoicechatServerApi api;
    private final AudioChannel channel;
    private final int bufferFrames;
    private final Runnable onEnded;

    private final Deque<JellyfinTrack> queue = new ArrayDeque<>();

    private AudioPlayer currentPlayer;
    private JellyfinPcmSource currentSource;
    private volatile JellyfinTrack nowPlaying;
    private volatile float volume;
    private boolean stopRequested = false;
    private volatile boolean sessionPaused = false;
    private JellyfinClient albumRadioClient;
    private String albumRadioExcludeId;
    /** Bumped by anything that should invalidate an in-flight async continuation fetch. */
    private int stateVersion = 0;

    private Consumer<JellyfinTrack> onTrackChanged = t -> {
    };

    public PlaybackSession(String key, JavaPlugin plugin, VoicechatServerApi api,
                            AudioChannel channel, int bufferFrames, float initialVolume, Runnable onEnded) {
        this.key = key;
        this.plugin = plugin;
        this.api = api;
        this.channel = channel;
        this.bufferFrames = bufferFrames;
        this.volume = initialVolume;
        this.onEnded = onEnded;
    }

    public String key() {
        return key;
    }

    /**
     * Re-registers a (re)connected player's fresh {@link VoicechatConnection}
     * as an explicit target on this session's channel, if it's a static one.
     * A stale connection object from before a reconnect won't receive audio,
     * so this needs to be called again whenever a relevant player (re)joins.
     */
    public void ensureTarget(VoicechatConnection connection) {
        if (channel instanceof StaticAudioChannel staticChannel) {
            staticChannel.addTarget(connection);
        }
    }

    /** Opposite of {@link #ensureTarget} - stops a specific connection from receiving this channel's audio. */
    public void removeTarget(VoicechatConnection connection) {
        if (channel instanceof StaticAudioChannel staticChannel) {
            staticChannel.removeTarget(connection);
        }
    }

    public JellyfinTrack nowPlaying() {
        return nowPlaying;
    }

    public List<JellyfinTrack> queueSnapshot() {
        return List.copyOf(queue);
    }

    public boolean isPaused() {
        return sessionPaused;
    }

    public void onTrackChanged(Consumer<JellyfinTrack> listener) {
        this.onTrackChanged = listener;
    }

    /** Clears the queue and immediately plays this track. Call only from the main thread. */
    public void playNow(JellyfinTrack track) {
        albumRadioClient = null;
        stateVersion++;
        queue.clear();
        queue.addFirst(track);
        if (currentPlayer != null) {
            // onStopped (below) will see the new head of queue and advance to it.
            currentPlayer.stopPlaying();
        } else {
            advance();
        }
    }

    public void enqueue(JellyfinTrack track) {
        queue.addLast(track);
        if (nowPlaying == null && currentPlayer == null) {
            advance();
        }
    }

    public void skip() {
        if (currentPlayer != null) {
            currentPlayer.stopPlaying();
        } else {
            advance();
        }
    }

    public void stopAll() {
        stopRequested = true;
        albumRadioClient = null;
        stateVersion++;
        queue.clear();
        if (currentSource != null) {
            currentSource.stop();
        }
        if (currentPlayer != null) {
            currentPlayer.stopPlaying();
        } else {
            finish();
        }
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(2f, v));
        if (currentSource != null) {
            currentSource.setVolume(volume);
        }
    }

    public float volume() {
        return volume;
    }

    public void setPaused(boolean paused) {
        this.sessionPaused = paused;
        if (currentSource != null) {
            currentSource.setPaused(paused);
        }
    }

    /**
     * Turns on "album radio": once the queue this album filled runs dry, a
     * different random album from the same server is fetched and queued up
     * automatically, indefinitely, until something else is played or the
     * session is stopped. Call this right after queuing up an album's
     * tracks (after {@link #playNow}, which would otherwise clear it).
     */
    public void enableAlbumRadio(JellyfinClient client, String currentAlbumId) {
        this.albumRadioClient = client;
        this.albumRadioExcludeId = currentAlbumId;
    }

    private void advance() {
        JellyfinTrack next = queue.pollFirst();
        if (next == null) {
            JellyfinTrack justFinished = nowPlaying;
            nowPlaying = null;
            if (justFinished != null && justFinished.albumId() != null && !justFinished.albumId().isBlank()) {
                continueSameAlbum(justFinished);
            } else if (albumRadioClient != null) {
                continueAlbumRadio();
            } else {
                finish();
            }
            return;
        }
        nowPlaying = next;
        onTrackChanged.accept(next);

        // Tear down the previous track's reader thread/HTTP connection before
        // starting a new one - leaving it running would leak resources, and
        // per Simple Voice Chat's own docs a channel must never have more than
        // one AudioPlayer/source feeding it at a time.
        if (currentSource != null) {
            currentSource.stop();
            currentSource = null;
        }

        JellyfinPcmSource source = new JellyfinPcmSource(api, next.client(), key, next.id(), bufferFrames, plugin.getLogger());
        source.setVolume(volume);
        source.setPaused(sessionPaused);
        source.setOnFinished(() -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (currentSource == source && currentPlayer != null) {
                currentPlayer.stopPlaying();
            }
        }));

        AudioPlayer player = api.createAudioPlayer(channel, api.createEncoder(), source::nextFrame);
        player.setOnStopped(() -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (currentPlayer != player) {
                // Already superseded by a later track; nothing to do.
                return;
            }
            currentPlayer = null;
            currentSource = null;
            if (stopRequested) {
                finish();
            } else {
                advance();
            }
        }));

        this.currentSource = source;
        this.currentPlayer = player;
        source.start();
        player.startPlaying();
    }

    /**
     * A single track that belongs to an album finished with nothing else
     * queued: fetch that album's full, correctly-ordered track list, find
     * where this track sits, and queue up whatever comes after it. Falls
     * back to album radio (if that happens to already be on for this
     * session) or a normal end if there's nothing left in the album.
     */
    private void continueSameAlbum(JellyfinTrack finishedTrack) {
        JellyfinClient client = finishedTrack.client();
        String albumId = finishedTrack.albumId();
        String finishedId = finishedTrack.id();
        int expectedVersion = stateVersion;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<JellyfinTrack> albumTracks;
            try {
                albumTracks = client.getAlbumItems(albumId);
            } catch (Exception e) {
                plugin.getLogger().warning("Album continuation: failed to load album - " + e.getMessage());
                albumTracks = List.of();
            }
            int idx = -1;
            for (int i = 0; i < albumTracks.size(); i++) {
                if (albumTracks.get(i).id().equals(finishedId)) {
                    idx = i;
                    break;
                }
            }
            List<JellyfinTrack> rest = (idx >= 0 && idx + 1 < albumTracks.size())
                    ? albumTracks.subList(idx + 1, albumTracks.size())
                    : List.of();
            List<JellyfinTrack> finalRest = rest;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (stateVersion != expectedVersion) {
                    // playNow/stop happened while this fetch was in flight.
                    return;
                }
                if (finalRest.isEmpty()) {
                    if (albumRadioClient != null) {
                        continueAlbumRadio();
                    } else {
                        finish();
                    }
                    return;
                }
                queue.addAll(finalRest);
                advance();
            });
        });
    }

    /**
     * Picks a different random album from the same server and queues its
     * tracks, then resumes playback - or gives up quietly (ending the
     * session normally) if that album list can't be fetched or is empty.
     */
    private void continueAlbumRadio() {
        JellyfinClient client = albumRadioClient;
        String excludeId = albumRadioExcludeId;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<JellyfinAlbum> albums;
            try {
                albums = client.listAlbums();
            } catch (Exception e) {
                plugin.getLogger().warning("Album radio: failed to list albums - " + e.getMessage());
                albums = List.of();
            }
            JellyfinAlbum chosen = pickRandomAlbum(albums, excludeId);
            List<JellyfinTrack> tracks = List.of();
            if (chosen != null) {
                try {
                    tracks = chosen.client().getAlbumItems(chosen.id());
                } catch (Exception e) {
                    plugin.getLogger().warning("Album radio: failed to load album \"" + chosen.name() + "\" - " + e.getMessage());
                }
            }
            JellyfinAlbum finalChosen = chosen;
            List<JellyfinTrack> finalTracks = tracks;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // Radio mode may have been cancelled (stop/playNow/a newer
                // continuation) while this fetch was in flight - bail out.
                if (albumRadioClient != client) {
                    return;
                }
                if (finalChosen == null || finalTracks.isEmpty()) {
                    finish();
                    return;
                }
                queue.addAll(finalTracks);
                albumRadioExcludeId = finalChosen.id();
                advance();
            });
        });
    }

    private static JellyfinAlbum pickRandomAlbum(List<JellyfinAlbum> albums, String excludeId) {
        if (albums.isEmpty()) {
            return null;
        }
        List<JellyfinAlbum> withoutCurrent = albums.stream().filter(a -> !a.id().equals(excludeId)).toList();
        List<JellyfinAlbum> pool = withoutCurrent.isEmpty() ? albums : withoutCurrent;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private void finish() {
        stopRequested = false;
        nowPlaying = null;
        currentPlayer = null;
        currentSource = null;
        if (onEnded != null) {
            onEnded.run();
        }
    }

    /** Kicks off playback if this session was created with a non-empty starting queue. */
    public void start() {
        if (nowPlaying == null && currentPlayer == null) {
            advance();
        }
    }
}
