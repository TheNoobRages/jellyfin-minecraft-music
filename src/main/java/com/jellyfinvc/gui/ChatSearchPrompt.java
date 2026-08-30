package com.jellyfinvc.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets a GUI ask a player to type free text in chat (there's no native
 * text-input widget in a vanilla inventory screen). Used for the search box,
 * since players browsing the library won't usually know an exact song name
 * to type as a slash-command argument.
 */
public final class ChatSearchPrompt implements Listener {

    private static final long TIMEOUT_TICKS = 20L * 30;

    private final JavaPlugin plugin;
    private final Map<UUID, PendingPrompt> pending = new ConcurrentHashMap<>();

    public ChatSearchPrompt(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private record PendingPrompt(Consumer<String> onSubmit, BukkitTask timeout) {
    }

    public void prompt(Player player, String promptMessage, Consumer<String> onSubmit) {
        cancel(player);
        player.sendMessage(Component.text(promptMessage, NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Type it in chat, or type 'cancel'.", NamedTextColor.GRAY));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) != null) {
                player.sendMessage(Component.text("Search timed out.", NamedTextColor.RED));
            }
        }, TIMEOUT_TICKS);
        pending.put(player.getUniqueId(), new PendingPrompt(onSubmit, timeout));
    }

    private void cancel(Player player) {
        PendingPrompt old = pending.remove(player.getUniqueId());
        if (old != null) {
            old.timeout().cancel();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingPrompt p = pending.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        p.timeout().cancel();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Component.text("Search cancelled.", NamedTextColor.GRAY));
                return;
            }
            if (text.isBlank()) {
                player.sendMessage(Component.text("Empty search - try again.", NamedTextColor.RED));
                return;
            }
            p.onSubmit().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer());
    }
}
