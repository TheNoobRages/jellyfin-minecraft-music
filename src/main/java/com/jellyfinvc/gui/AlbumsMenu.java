package com.jellyfinvc.gui;

import com.jellyfinvc.JellyfinVoiceChatPlugin;
import com.jellyfinvc.audio.PlaybackSession;
import com.jellyfinvc.jellyfin.JellyfinAlbum;
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
 * Lists Jellyfin music albums. Clicking one browses its tracks (in original
 * album order); shift-clicking plays the whole album immediately.
 */
public final class AlbumsMenu {

    private static final int SLOTS = 45;
    private static final Map<Inventory, AlbumsMenu> OPEN_MENUS = new WeakHashMap<>();

    private final JellyfinVoiceChatPlugin plugin;
    private final Player viewer;
    private final List<JellyfinAlbum> albums;
    private final Supplier<PlaybackSession> targetSupplier;
    private final Runnable onBack;
    private final Inventory inventory;

    public AlbumsMenu(JellyfinVoiceChatPlugin plugin, Player viewer, List<JellyfinAlbum> albums,
                       Supplier<PlaybackSession> targetSupplier, Runnable onBack) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.albums = albums;
        this.targetSupplier = targetSupplier;
        this.onBack = onBack;
        this.inventory = Bukkit.createInventory(null, 54, Component.text("Albums", NamedTextColor.DARK_AQUA));
        render();
    }

    public static AlbumsMenu forInventory(Inventory inventory) {
        return OPEN_MENUS.get(inventory);
    }

    public static void forget(Inventory inventory) {
        OPEN_MENUS.remove(inventory);
    }

    public void open() {
        OPEN_MENUS.put(inventory, this);
        viewer.openInventory(inventory);
    }

    public void handleClick(int slot, boolean shiftClick, Player clicker) {
        if (slot < 0 || slot >= Math.min(albums.size(), SLOTS)) {
            if (slot == 53) {
                clicker.closeInventory();
            } else if (slot == 52 && onBack != null) {
                onBack.run();
            }
            return;
        }
        JellyfinAlbum album = albums.get(slot);
        clicker.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<JellyfinTrack> tracks;
            try {
                tracks = album.client().getAlbumItems(album.id());
            } catch (Exception e) {
                plugin.getLogger().warning("Jellyfin album load failed: " + e.getMessage());
                tracks = List.of();
            }
            List<JellyfinTrack> finalTracks = tracks;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalTracks.isEmpty()) {
                    clicker.sendMessage(Component.text("\"" + album.name() + "\" has no tracks.", NamedTextColor.RED));
                    return;
                }
                PlaybackSession session = targetSupplier.get();
                boolean idle = session == null || (session.nowPlaying() == null && session.queueSnapshot().isEmpty());
                if (shiftClick || idle) {
                    if (session == null) {
                        clicker.sendMessage(Component.text("Couldn't start playback here.", NamedTextColor.RED));
                        return;
                    }
                    session.playNow(finalTracks.get(0));
                    for (int i = 1; i < finalTracks.size(); i++) {
                        session.enqueue(finalTracks.get(i));
                    }
                    var manager = plugin.playbackManager();
                    boolean radio = manager != null && manager.isAlbumRadioEnabled(clicker);
                    if (radio) {
                        session.enableAlbumRadio(album.client(), album.id());
                    }
                    clicker.sendMessage(Component.text("Playing album \"" + album.name() + "\" ("
                            + finalTracks.size() + " tracks)."
                            + (radio ? " Another album will play next when it ends." : ""), NamedTextColor.AQUA));
                } else {
                    new BrowseMenu(clicker, album.display(), finalTracks, targetSupplier,
                            () -> new AlbumsMenu(plugin, clicker, albums, targetSupplier, onBack).open()).open();
                }
            });
        });
    }

    private void render() {
        inventory.clear();
        int max = Math.min(albums.size(), SLOTS);
        for (int i = 0; i < max; i++) {
            inventory.setItem(i, albumItem(albums.get(i)));
        }
        if (onBack != null) {
            ItemStack back = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta backMeta = back.getItemMeta();
            backMeta.displayName(Component.text("Back", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            back.setItemMeta(backMeta);
            inventory.setItem(52, back);
        }
        ItemStack close = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("Close", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(meta);
        inventory.setItem(53, close);
    }

    private ItemStack albumItem(JellyfinAlbum album) {
        ItemStack item = new ItemStack(Material.MUSIC_DISC_CAT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(album.name(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(album.artist() == null || album.artist().isBlank() ? "" : album.artist(), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to play (or browse if something's already playing)", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Shift-click to always play all", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }
}
