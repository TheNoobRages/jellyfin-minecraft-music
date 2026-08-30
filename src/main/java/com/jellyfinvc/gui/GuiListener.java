package com.jellyfinvc.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isOurs = BrowseMenu.forInventory(top) != null
                || MainMenu.forInventory(top) != null
                || PlaylistsMenu.forInventory(top) != null
                || AlbumsMenu.forInventory(top) != null;
        if (!isOurs) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != top) {
            // Clicks in the player's own inventory while the menu is open are fine.
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        BrowseMenu browse = BrowseMenu.forInventory(top);
        if (browse != null) {
            browse.handleClick(event.getSlot(), event.isShiftClick(), player);
            return;
        }
        MainMenu main = MainMenu.forInventory(top);
        if (main != null) {
            main.handleClick(event.getSlot(), player);
            return;
        }
        PlaylistsMenu playlists = PlaylistsMenu.forInventory(top);
        if (playlists != null) {
            playlists.handleClick(event.getSlot(), event.isShiftClick(), player);
            return;
        }
        AlbumsMenu albums = AlbumsMenu.forInventory(top);
        if (albums != null) {
            albums.handleClick(event.getSlot(), event.isShiftClick(), player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (BrowseMenu.forInventory(top) != null || MainMenu.forInventory(top) != null
                || PlaylistsMenu.forInventory(top) != null || AlbumsMenu.forInventory(top) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        BrowseMenu.forget(event.getInventory());
        MainMenu.forget(event.getInventory());
        PlaylistsMenu.forget(event.getInventory());
        AlbumsMenu.forget(event.getInventory());
    }
}
