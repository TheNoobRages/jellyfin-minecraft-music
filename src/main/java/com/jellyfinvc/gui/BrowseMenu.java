package com.jellyfinvc.gui;

import com.jellyfinvc.audio.PlaybackSession;
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
 * A simple 54-slot inventory GUI: search results in the top five rows, a
 * transport control row (pause/skip/stop/volume) on the bottom row.
 *
 * <p>Each menu is bound to a "target" - a supplier that resolves (creating if
 * necessary) the {@link PlaybackSession} that clicking a track should play
 * into. That lets the same menu class serve /jf gui (personal), /jf jukebox
 * browse, /jf group browse, and /jf broadcast browse.
 */
public final class BrowseMenu {

    private static final int TRACK_SLOTS = 45;
    private static final Map<Inventory, BrowseMenu> OPEN_MENUS = new WeakHashMap<>();

    private final Player viewer;
    private final List<JellyfinTrack> tracks;
    private final Supplier<PlaybackSession> targetSupplier;
    private final Runnable onBack;
    private final Inventory inventory;

    public BrowseMenu(Player viewer, String title, List<JellyfinTrack> tracks, Supplier<PlaybackSession> targetSupplier,
                       Runnable onBack) {
        this.viewer = viewer;
        this.tracks = tracks;
        this.targetSupplier = targetSupplier;
        this.onBack = onBack;
        this.inventory = Bukkit.createInventory(null, 54, Component.text(title, NamedTextColor.DARK_AQUA));
        render();
    }

    public static BrowseMenu forInventory(Inventory inventory) {
        return OPEN_MENUS.get(inventory);
    }

    public static void forget(Inventory inventory) {
        OPEN_MENUS.remove(inventory);
    }

    public void open() {
        OPEN_MENUS.put(inventory, this);
        viewer.openInventory(inventory);
    }

    public Inventory inventory() {
        return inventory;
    }

    public void handleClick(int slot, boolean shiftClick, Player clicker) {
        if (slot >= 0 && slot < TRACK_SLOTS && slot < tracks.size()) {
            JellyfinTrack track = tracks.get(slot);
            PlaybackSession session = targetSupplier.get();
            if (session == null) {
                clicker.sendMessage(Component.text("Couldn't start playback here.", NamedTextColor.RED));
                return;
            }
            if (shiftClick) {
                session.enqueue(track);
                clicker.sendMessage(Component.text("Queued: ", NamedTextColor.GRAY)
                        .append(Component.text(track.display(), NamedTextColor.AQUA)));
            } else {
                session.playNow(track);
                clicker.sendMessage(Component.text("Now playing: ", NamedTextColor.GRAY)
                        .append(Component.text(track.display(), NamedTextColor.AQUA)));
            }
            render();
            return;
        }

        PlaybackSession session = targetSupplier.get();
        switch (slot) {
            case 45 -> {
                if (session != null) {
                    session.setPaused(!session.isPaused());
                    render();
                }
            }
            case 46 -> {
                if (session != null) {
                    session.skip();
                    render();
                }
            }
            case 47 -> {
                if (session != null) {
                    session.stopAll();
                    render();
                }
            }
            case 51 -> {
                if (session != null) {
                    session.setVolume(session.volume() - 0.1f);
                    render();
                }
            }
            case 52 -> {
                if (session != null) {
                    session.setVolume(session.volume() + 0.1f);
                    render();
                }
            }
            case 48 -> {
                if (onBack != null) {
                    onBack.run();
                }
            }
            case 53 -> clicker.closeInventory();
            default -> {
            }
        }
    }

    private void render() {
        inventory.clear();
        int max = Math.min(tracks.size(), TRACK_SLOTS);
        for (int i = 0; i < max; i++) {
            inventory.setItem(i, trackItem(tracks.get(i)));
        }

        PlaybackSession session = targetSupplier.get();
        inventory.setItem(45, controlItem(Material.PAPER,
                session != null && session.isPaused() ? "Resume" : "Pause"));
        inventory.setItem(46, controlItem(Material.ARROW, "Skip"));
        inventory.setItem(47, controlItem(Material.BARRIER, "Stop"));
        if (onBack != null) {
            inventory.setItem(48, controlItem(Material.SPECTRAL_ARROW, "Back"));
        }
        inventory.setItem(49, nowPlayingItem(session));
        inventory.setItem(51, controlItem(Material.REDSTONE_TORCH, "Volume -10%"));
        inventory.setItem(52, controlItem(Material.GLOWSTONE_DUST, "Volume +10%"));
        inventory.setItem(53, controlItem(Material.OAK_DOOR, "Close"));
    }

    private ItemStack trackItem(JellyfinTrack track) {
        ItemStack item = new ItemStack(Material.MUSIC_DISC_13);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(track.display(), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(track.album() == null || track.album().isBlank() ? "" : track.album(), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Length: " + track.durationLabel(), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to play, shift-click to queue", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
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
