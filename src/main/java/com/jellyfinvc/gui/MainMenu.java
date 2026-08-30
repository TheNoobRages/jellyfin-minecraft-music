package com.jellyfinvc.gui;

import com.jellyfinvc.JellyfinVoiceChatPlugin;
import com.jellyfinvc.audio.PlaybackSession;
import com.jellyfinvc.jellyfin.JellyfinPlaylist;
import com.jellyfinvc.jellyfin.JellyfinTrack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The landing screen opened by {@code /music}: search, browse playlists, and
 * transport controls for whichever playback context (personal/jukebox/group/
 * broadcast) it was opened for.
 */
public final class MainMenu {

    private static final Map<Inventory, MainMenu> OPEN_MENUS = new WeakHashMap<>();

    private final JellyfinVoiceChatPlugin plugin;
    private final Player viewer;
    private final String title;
    private final Supplier<PlaybackSession> targetSupplier;
    private final Inventory inventory;

    public MainMenu(JellyfinVoiceChatPlugin plugin, Player viewer, String title, Supplier<PlaybackSession> targetSupplier) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.title = title;
        this.targetSupplier = targetSupplier;
        this.inventory = Bukkit.createInventory(null, 27, Component.text(title, NamedTextColor.DARK_AQUA));
        render();
    }

    /** How a child menu (search results, playlists, albums) gets back here. */
    private Runnable backToHere(Player player) {
        return () -> new MainMenu(plugin, player, title, targetSupplier).open();
    }

    public static MainMenu forInventory(Inventory inventory) {
        return OPEN_MENUS.get(inventory);
    }

    public static void forget(Inventory inventory) {
        OPEN_MENUS.remove(inventory);
    }

    public void open() {
        OPEN_MENUS.put(inventory, this);
        viewer.openInventory(inventory);
    }

    public void handleClick(int slot, Player clicker) {
        PlaybackSession session = targetSupplier.get();
        switch (slot) {
            case 2 -> {
                clicker.closeInventory();
                if (!requireConfigured(clicker)) {
                    return;
                }
                plugin.chatSearchPrompt().prompt(clicker,
                        "Type a song, artist, or album to search for:",
                        query -> runSearch(clicker, query));
            }
            case 4 -> {
                clicker.closeInventory();
                if (!requireConfigured(clicker)) {
                    return;
                }
                var client = plugin.jellyfinRegistry().clientFor(clicker);
                runAsync(
                        () -> {
                            try {
                                return client.listPlaylists();
                            } catch (Exception e) {
                                plugin.getLogger().warning("Jellyfin playlist listing failed: " + e.getMessage());
                                return List.<JellyfinPlaylist>of();
                            }
                        },
                        playlists -> {
                            if (playlists.isEmpty()) {
                                clicker.sendMessage(Component.text("No playlists found on your Jellyfin server.", NamedTextColor.RED));
                                return;
                            }
                            new PlaylistsMenu(plugin, clicker, playlists, targetSupplier, backToHere(clicker)).open();
                        });
            }
            case 6 -> {
                clicker.closeInventory();
                if (!requireConfigured(clicker)) {
                    return;
                }
                var client = plugin.jellyfinRegistry().clientFor(clicker);
                runAsync(
                        () -> {
                            try {
                                return client.listAlbums();
                            } catch (Exception e) {
                                plugin.getLogger().warning("Jellyfin album listing failed: " + e.getMessage());
                                return List.<com.jellyfinvc.jellyfin.JellyfinAlbum>of();
                            }
                        },
                        albums -> {
                            if (albums.isEmpty()) {
                                clicker.sendMessage(Component.text("No albums found on your Jellyfin server.", NamedTextColor.RED));
                                return;
                            }
                            new AlbumsMenu(plugin, clicker, albums, targetSupplier, backToHere(clicker)).open();
                        });
            }
            case 8 -> {
                clicker.closeInventory();
                String url = plugin.jellyfinRegistry().personalServerUrl(clicker);
                clicker.sendMessage(Component.text(url == null
                                ? "You're using the shared Jellyfin server. Use /music myserver to connect your own."
                                : "Your personal Jellyfin server: " + url + " (use /music myserver clear to remove it).",
                        NamedTextColor.GRAY));
            }
            case 18 -> {
                if (session != null) {
                    session.setPaused(!session.isPaused());
                    render();
                }
            }
            case 19 -> {
                if (session != null) {
                    session.skip();
                    render();
                }
            }
            case 20 -> {
                if (session != null) {
                    session.stopAll();
                    render();
                }
            }
            case 21 -> {
                var manager = plugin.playbackManager();
                if (manager != null) {
                    boolean nowMuted = !manager.isMuted(clicker);
                    manager.setMuted(clicker, nowMuted);
                    clicker.sendMessage(nowMuted
                            ? Component.text("Muted all Jellyfin audio for you.", NamedTextColor.GRAY)
                            : Component.text("Unmuted.", NamedTextColor.AQUA));
                    render();
                }
            }
            case 24 -> {
                if (session != null) {
                    session.setVolume(session.volume() - 0.1f);
                    render();
                }
            }
            case 25 -> {
                if (session != null) {
                    session.setVolume(session.volume() + 0.1f);
                    render();
                }
            }
            case 26 -> clicker.closeInventory();
            default -> {
            }
        }
    }

    private boolean requireConfigured(Player player) {
        if (plugin.jellyfinRegistry().clientFor(player).isConfigured()) {
            return true;
        }
        boolean hasPersonal = plugin.jellyfinRegistry().hasPersonalServer(player);
        player.sendMessage(Component.text(hasPersonal
                        ? "Your personal Jellyfin server isn't reachable right now."
                        : "No Jellyfin server is set up yet. Connect your own with /music myserver, "
                        + "or ask a server admin to configure the shared one.",
                NamedTextColor.RED));
        return false;
    }

    private void runSearch(Player player, String query) {
        var client = plugin.jellyfinRegistry().clientFor(player);
        runAsync(
                () -> {
                    try {
                        return client.search(query, plugin.pluginConfig().searchLimit());
                    } catch (Exception e) {
                        plugin.getLogger().warning("Jellyfin search failed: " + e.getMessage());
                        return List.<JellyfinTrack>of();
                    }
                },
                tracks -> {
                    if (tracks.isEmpty()) {
                        player.sendMessage(Component.text("No matching tracks found for \"" + query + "\".", NamedTextColor.RED));
                        return;
                    }
                    new BrowseMenu(player, "Results: " + query, tracks, targetSupplier, backToHere(player)).open();
                });
    }

    private <T> void runAsync(Supplier<T> work, java.util.function.Consumer<T> onMainThread) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = work.get();
            Bukkit.getScheduler().runTask(plugin, () -> onMainThread.accept(result));
        });
    }

    private void render() {
        inventory.clear();
        inventory.setItem(2, icon(Material.COMPASS, "Search Library",
                "Search by song, artist, or album", "Click to type a search"));
        inventory.setItem(4, icon(Material.WRITABLE_BOOK, "Browse Playlists",
                "See your Jellyfin playlists"));
        inventory.setItem(6, icon(Material.MUSIC_DISC_CAT, "Browse Albums",
                "See albums, click one to see its songs"));
        inventory.setItem(8, myServerItem());

        PlaybackSession session = targetSupplier.get();
        inventory.setItem(18, controlItem(Material.PAPER, session != null && session.isPaused() ? "Resume" : "Pause"));
        inventory.setItem(19, controlItem(Material.ARROW, "Skip"));
        inventory.setItem(20, controlItem(Material.BARRIER, "Stop"));
        inventory.setItem(21, muteItem());
        inventory.setItem(22, nowPlayingItem(session));
        inventory.setItem(24, controlItem(Material.REDSTONE_TORCH, "Volume -10%"));
        inventory.setItem(25, controlItem(Material.GLOWSTONE_DUST, "Volume +10%"));
        inventory.setItem(26, controlItem(Material.OAK_DOOR, "Close"));
    }

    private ItemStack myServerItem() {
        String url = plugin.jellyfinRegistry().personalServerUrl(viewer);
        ItemStack item = new ItemStack(Material.ENDER_EYE);
        ItemMeta meta = item.getItemMeta();
        if (url == null) {
            meta.displayName(Component.text("My Jellyfin Server", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Using the shared server", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Run /music myserver to connect your own", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.displayName(Component.text("My Jellyfin Server", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(url, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Run /music myserver clear to remove it", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack icon(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(lore).stream()
                .map(l -> Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack controlItem(Material material, String label) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack muteItem() {
        var manager = plugin.playbackManager();
        boolean muted = manager != null && manager.isMuted(viewer);
        ItemStack item = new ItemStack(Material.BELL);
        ItemMeta meta = item.getItemMeta();
        if (muted) {
            meta.displayName(Component.text("Unmute All", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("You won't hear any Jellyfin audio right now", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        } else {
            meta.displayName(Component.text("Mute All", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Silence personal/group/broadcast/jukebox audio for you", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack nowPlayingItem(PlaybackSession session) {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        JellyfinTrack track = session == null ? null : session.nowPlaying();
        if (track == null) {
            meta.displayName(Component.text("Nothing playing", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            meta.displayName(Component.text(track.display(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text((session.isPaused() ? "Paused" : "Playing")
                            + " - Volume " + Math.round(session.volume() * 100) + "%", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(session.queueSnapshot().size() + " queued", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
        }
        item.setItemMeta(meta);
        return item;
    }
}
