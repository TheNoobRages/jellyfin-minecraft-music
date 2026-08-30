package com.jellyfinvc.commands;

import com.jellyfinvc.JellyfinVoiceChatPlugin;
import com.jellyfinvc.audio.JukeboxInfo;
import com.jellyfinvc.audio.PlaybackManager;
import com.jellyfinvc.audio.PlaybackSession;
import com.jellyfinvc.gui.BrowseMenu;
import com.jellyfinvc.gui.MainMenu;
import com.jellyfinvc.jellyfin.JellyfinClient;
import com.jellyfinvc.jellyfin.JellyfinTrack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class MusicCommand implements CommandExecutor, TabCompleter {

    private final JellyfinVoiceChatPlugin plugin;
    private final Map<UUID, UUID> lastJukeboxByOwner = new ConcurrentHashMap<>();

    public MusicCommand(JellyfinVoiceChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PlaybackManager manager = plugin.playbackManager();
        if (manager == null) {
            sender.sendMessage(error("Still waiting on Simple Voice Chat to finish starting up - try again in a moment."));
            return true;
        }
        if (args.length == 0) {
            personal(sender, p -> new MainMenu(plugin, p, "Music", () -> manager.personalSession(p)).open());
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] rest = tail(args, 1);

        switch (sub) {
            case "play" -> personal(sender, p -> personalAction(manager, p, "play", rest));
            case "queue" -> personal(sender, p -> personalAction(manager, p, "queue", rest));
            case "search" -> personal(sender, p -> personalAction(manager, p, "search", rest));
            case "playlist" -> personal(sender, p -> personalAction(manager, p, "playlist", rest));
            case "pause", "resume", "stop", "skip" -> personal(sender, p ->
                    control(sender, manager.personalSession(p), sub, rest));
            case "volume" -> personal(sender, p -> control(sender, manager.personalSession(p), "volume", rest));
            case "gui", "menu", "nowplaying" -> personal(sender, p ->
                    new MainMenu(plugin, p, "Music", () -> manager.personalSession(p)).open());
            case "jukebox" -> personal(sender, p -> jukebox(manager, p, rest));
            case "group" -> personal(sender, p -> group(manager, p, rest));
            case "broadcast" -> broadcast(sender, manager, rest);
            case "mute" -> personal(sender, p -> {
                manager.setMuted(p, true);
                p.sendMessage(info("Muted all Jellyfin audio for you."));
            });
            case "unmute" -> personal(sender, p -> {
                manager.setMuted(p, false);
                p.sendMessage(info("Unmuted."));
            });
            case "myserver" -> personal(sender, p -> myserver(p, rest));
            case "albumradio" -> personal(sender, p -> albumRadio(manager, p, rest));
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ---- personal ----

    private void personalAction(PlaybackManager manager, Player p, String action, String[] rest) {
        if (rest.length == 0) {
            p.sendMessage(error("Usage: /music " + action + " <search terms>"));
            return;
        }
        String query = String.join(" ", rest);
        PlaybackSession session = manager.personalSession(p);
        switch (action) {
            case "play" -> searchAndAct(p, query, 1, tracks -> playFirst(p, session, tracks));
            case "queue" -> searchAndAct(p, query, 1, tracks -> queueFirst(p, session, tracks));
            case "search" -> openGuiForSearch(p, () -> manager.personalSession(p), "Results: " + query, query, "Music");
            case "playlist" -> playPlaylist(p, session, query);
            default -> {
            }
        }
    }

    private void playFirst(Player p, PlaybackSession session, List<JellyfinTrack> tracks) {
        if (tracks.isEmpty()) {
            p.sendMessage(error("No matching tracks found."));
            return;
        }
        session.playNow(tracks.get(0));
        p.sendMessage(info("Now playing: " + tracks.get(0).display()));
    }

    private void queueFirst(Player p, PlaybackSession session, List<JellyfinTrack> tracks) {
        if (tracks.isEmpty()) {
            p.sendMessage(error("No matching tracks found."));
            return;
        }
        session.enqueue(tracks.get(0));
        p.sendMessage(info("Queued: " + tracks.get(0).display()));
    }

    private void playPlaylist(Player p, PlaybackSession session, String name) {
        JellyfinClient client = clientFor(p);
        if (!requireConfigured(p, client)) {
            return;
        }
        runAsync(() -> {
            try {
                String playlistId = client.resolvePlaylistId(name);
                if (playlistId == null) {
                    return List.<JellyfinTrack>of();
                }
                return client.getPlaylistItems(playlistId);
            } catch (Exception e) {
                plugin.getLogger().warning("Jellyfin playlist lookup failed: " + e.getMessage());
                return List.<JellyfinTrack>of();
            }
        }, tracks -> {
            if (tracks.isEmpty()) {
                p.sendMessage(error("Playlist \"" + name + "\" not found or empty."));
                return;
            }
            session.playNow(tracks.get(0));
            for (int i = 1; i < tracks.size(); i++) {
                session.enqueue(tracks.get(i));
            }
            p.sendMessage(info("Playing playlist \"" + name + "\" (" + tracks.size() + " tracks)."));
        });
    }

    private void openGuiForSearch(Player p, Supplier<PlaybackSession> target, String title, String query,
                                   String menuTitle) {
        JellyfinClient client = clientFor(p);
        if (!requireConfigured(p, client)) {
            return;
        }
        runAsync(() -> {
            try {
                return client.search(query, plugin.pluginConfig().searchLimit());
            } catch (Exception e) {
                plugin.getLogger().warning("Jellyfin search failed: " + e.getMessage());
                return List.<JellyfinTrack>of();
            }
        }, tracks -> {
            if (tracks.isEmpty()) {
                p.sendMessage(error("No matching tracks found."));
                return;
            }
            new BrowseMenu(p, title, tracks, target,
                    () -> new MainMenu(plugin, p, menuTitle, target).open()).open();
        });
    }

    // ---- jukebox ----

    private void jukebox(PlaybackManager manager, Player p, String[] args) {
        if (!p.hasPermission("jellyfinvc.jukebox")) {
            p.sendMessage(error("You don't have permission to use jukeboxes."));
            return;
        }
        if (args.length == 0) {
            p.sendMessage(error("Usage: /music jukebox <place|menu|stop|list|play|search> [args]"));
            return;
        }
        String action = args[0].toLowerCase();
        String[] rest = tail(args, 1);
        switch (action) {
            case "place" -> {
                Location loc = p.getLocation();
                JukeboxInfo jbInfo = manager.placeJukebox(p, loc);
                lastJukeboxByOwner.put(p.getUniqueId(), jbInfo.id());
                manager.get(jbInfo.sessionKey()).start();
                p.sendMessage(info("Jukebox placed here. Use /music jukebox menu to control it."));
            }
            case "stop" -> {
                JukeboxInfo target = resolveOwnedJukebox(manager, p);
                if (target == null) {
                    p.sendMessage(error("No jukebox found. Place one with /music jukebox place."));
                    return;
                }
                manager.stopJukebox(target.id());
                p.sendMessage(info("Jukebox stopped."));
            }
            case "list" -> {
                List<JukeboxInfo> owned = manager.jukeboxesOwnedBy(p.getUniqueId());
                if (owned.isEmpty()) {
                    p.sendMessage(info("You have no active jukeboxes."));
                } else {
                    for (JukeboxInfo j : owned) {
                        p.sendMessage(Component.text("- " + j.id().toString().substring(0, 8) + " at "
                                        + j.location().getBlockX() + "," + j.location().getBlockY() + "," + j.location().getBlockZ(),
                                NamedTextColor.GRAY));
                    }
                }
            }
            case "menu" -> {
                JukeboxInfo target = resolveOwnedJukebox(manager, p);
                if (target == null) {
                    p.sendMessage(error("Place a jukebox first with /music jukebox place."));
                    return;
                }
                new MainMenu(plugin, p, "Jukebox", () -> manager.get(target.sessionKey())).open();
            }
            case "play" -> {
                if (rest.length == 0) {
                    p.sendMessage(error("Usage: /music jukebox play <search terms>"));
                    return;
                }
                JukeboxInfo target = resolveOwnedJukebox(manager, p);
                if (target == null) {
                    p.sendMessage(error("Place a jukebox first with /music jukebox place."));
                    return;
                }
                PlaybackSession session = manager.get(target.sessionKey());
                String query = String.join(" ", rest);
                searchAndAct(p, query, 1, tracks -> playFirst(p, session, tracks));
            }
            case "search" -> {
                if (rest.length == 0) {
                    p.sendMessage(error("Usage: /music jukebox search <search terms>"));
                    return;
                }
                JukeboxInfo target = resolveOwnedJukebox(manager, p);
                if (target == null) {
                    p.sendMessage(error("Place a jukebox first with /music jukebox place."));
                    return;
                }
                String query = String.join(" ", rest);
                openGuiForSearch(p, () -> manager.get(target.sessionKey()), "Jukebox: " + query, query, "Jukebox");
            }
            case "pause", "resume", "skip", "volume" -> {
                JukeboxInfo target = resolveOwnedJukebox(manager, p);
                if (target == null) {
                    p.sendMessage(error("No jukebox found."));
                    return;
                }
                control(p, manager.get(target.sessionKey()), action, rest);
            }
            default -> p.sendMessage(error("Usage: /music jukebox <place|menu|stop|list|play|search> [args]"));
        }
    }

    private JukeboxInfo resolveOwnedJukebox(PlaybackManager manager, Player p) {
        UUID lastId = lastJukeboxByOwner.get(p.getUniqueId());
        List<JukeboxInfo> owned = manager.jukeboxesOwnedBy(p.getUniqueId());
        if (lastId != null) {
            for (JukeboxInfo j : owned) {
                if (j.id().equals(lastId)) {
                    return j;
                }
            }
        }
        return owned.isEmpty() ? null : owned.get(0);
    }

    // ---- group ----

    private void group(PlaybackManager manager, Player p, String[] args) {
        if (!p.hasPermission("jellyfinvc.group")) {
            p.sendMessage(error("You don't have permission to use group playback."));
            return;
        }
        PlaybackSession session = manager.groupSessionFor(p);
        if (session == null) {
            p.sendMessage(error("You need to be in a voice chat group first. Join or create one in the voice chat UI."));
            return;
        }
        if (args.length == 0) {
            new MainMenu(plugin, p, "Group", () -> manager.groupSessionFor(p)).open();
            return;
        }
        String action = args[0].toLowerCase();
        String[] rest = tail(args, 1);
        switch (action) {
            case "menu" -> new MainMenu(plugin, p, "Group", () -> manager.groupSessionFor(p)).open();
            case "play" -> {
                if (rest.length == 0) {
                    p.sendMessage(error("Usage: /music group play <search terms>"));
                    return;
                }
                String query = String.join(" ", rest);
                searchAndAct(p, query, 1, tracks -> playFirst(p, session, tracks));
            }
            case "search" -> {
                if (rest.length == 0) {
                    p.sendMessage(error("Usage: /music group search <search terms>"));
                    return;
                }
                String query = String.join(" ", rest);
                openGuiForSearch(p, () -> manager.groupSessionFor(p), "Group: " + query, query, "Group");
            }
            default -> control(p, session, action, rest);
        }
    }

    // ---- broadcast ----

    private void broadcast(CommandSender sender, PlaybackManager manager, String[] args) {
        // enable/disable is the admin kill switch for the whole feature -
        // separate from off/on below (a player's own choice to not hear it).
        // Checked first so it works even while broadcast is disabled or the
        // sender lacks jellyfinvc.broadcast.
        if (args.length > 0) {
            String maybeAdmin = args[0].toLowerCase();
            if (maybeAdmin.equals("enable") || maybeAdmin.equals("disable")) {
                if (!sender.hasPermission("jellyfinvc.admin")) {
                    sender.sendMessage(error("You don't have permission to do that."));
                    return;
                }
                boolean enable = maybeAdmin.equals("enable");
                manager.setBroadcastEnabled(enable);
                sender.sendMessage(info(enable
                        ? "Server-wide broadcast is now enabled."
                        : "Server-wide broadcast is now disabled - any broadcast playing was stopped, "
                        + "and no one can start a new one until it's re-enabled."));
                return;
            }
        }

        // off/on/toggle opt out of *hearing* broadcasts and are available to
        // everyone, unlike starting/controlling a broadcast which needs the
        // jellyfinvc.broadcast permission below. Handled first so this never
        // needs (or creates) a broadcast session just to flip the flag.
        if (args.length > 0) {
            String maybeOptOut = args[0].toLowerCase();
            if (maybeOptOut.equals("off") || maybeOptOut.equals("on") || maybeOptOut.equals("toggle")) {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(error("Only players can use that."));
                    return;
                }
                boolean newOptOut = switch (maybeOptOut) {
                    case "off" -> true;
                    case "on" -> false;
                    default -> !manager.isBroadcastOptedOut(p);
                };
                manager.setBroadcastOptOut(p, newOptOut);
                p.sendMessage(info(newOptOut
                        ? "You will no longer hear server-wide broadcasts. Use /music broadcast on to turn it back on."
                        : "You'll hear server-wide broadcasts again."));
                return;
            }
        }

        if (!sender.hasPermission("jellyfinvc.broadcast")) {
            sender.sendMessage(error("You don't have permission to use server-wide broadcast."));
            return;
        }
        if (!manager.isBroadcastEnabled()) {
            sender.sendMessage(error("Server-wide broadcast has been disabled by an admin."));
            return;
        }
        PlaybackSession session = manager.broadcastSession();
        if (args.length == 0) {
            if (sender instanceof Player p) {
                new MainMenu(plugin, p, "Broadcast", manager::broadcastSession).open();
            } else {
                sender.sendMessage(error("Usage: /music broadcast <play|menu|search|pause|resume|stop|skip|volume|off|on> [args]"));
            }
            return;
        }
        String action = args[0].toLowerCase();
        String[] rest = tail(args, 1);
        switch (action) {
            case "menu" -> {
                if (sender instanceof Player p) {
                    new MainMenu(plugin, p, "Broadcast", manager::broadcastSession).open();
                }
            }
            case "play" -> {
                if (rest.length == 0) {
                    sender.sendMessage(error("Usage: /music broadcast play <search terms>"));
                    return;
                }
                String query = String.join(" ", rest);
                searchAndActSender(sender, query, tracks -> {
                    if (tracks.isEmpty()) {
                        sender.sendMessage(error("No matching tracks found."));
                        return;
                    }
                    PlaybackSession current = manager.broadcastSession();
                    if (current == null) {
                        sender.sendMessage(error("Server-wide broadcast has been disabled by an admin."));
                        return;
                    }
                    current.playNow(tracks.get(0));
                    sender.sendMessage(info("Broadcasting: " + tracks.get(0).display()));
                });
            }
            case "search" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(error("Only players can use /music broadcast search."));
                    return;
                }
                if (rest.length == 0) {
                    p.sendMessage(error("Usage: /music broadcast search <search terms>"));
                    return;
                }
                String query = String.join(" ", rest);
                openGuiForSearch(p, manager::broadcastSession, "Broadcast: " + query, query, "Broadcast");
            }
            default -> control(sender, session, action, rest);
        }
    }

    // ---- album radio ----

    private void albumRadio(PlaybackManager manager, Player p, String[] args) {
        boolean current = manager.isAlbumRadioEnabled(p);
        if (args.length == 0) {
            p.sendMessage(info("Album radio (auto-play another album when one ends) is currently "
                    + (current ? "ON" : "OFF") + ". Use /music albumradio off|on to change it."));
            return;
        }
        String action = args[0].toLowerCase();
        boolean newValue = switch (action) {
            case "on" -> true;
            case "off" -> false;
            case "toggle" -> !current;
            default -> current;
        };
        if (!action.equals("on") && !action.equals("off") && !action.equals("toggle")) {
            p.sendMessage(error("Usage: /music albumradio off|on"));
            return;
        }
        manager.setAlbumRadioEnabled(p, newValue);
        p.sendMessage(info(newValue
                ? "Album radio is on - a new random album will play automatically when one you started ends."
                : "Album radio is off - albums you play will just stop when they end."));
    }

    // ---- personal jellyfin server ----

    private void myserver(Player p, String[] args) {
        if (!p.hasPermission("jellyfinvc.myserver")) {
            p.sendMessage(error("You don't have permission to connect your own Jellyfin server."));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            plugin.jellyfinRegistry().clearPersonalServer(p);
            p.sendMessage(info("Personal Jellyfin server removed - you're back on the shared server."));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            String url = plugin.jellyfinRegistry().personalServerUrl(p);
            p.sendMessage(info(url == null
                    ? "You're using the shared Jellyfin server."
                    : "Your personal Jellyfin server: " + url));
            return;
        }
        p.sendMessage(info("Let's connect your personal Jellyfin server (or use /music myserver clear/status)."));
        plugin.chatSearchPrompt().prompt(p, "Type your Jellyfin server URL (e.g. http://myserver:8096):", url ->
                plugin.chatSearchPrompt().prompt(p, "Now type your Jellyfin API key "
                        + "(Dashboard > API Keys > + in Jellyfin):", apiKey -> testAndSaveMyServer(p, url, apiKey)));
    }

    private void testAndSaveMyServer(Player p, String url, String apiKey) {
        p.sendMessage(info("Testing connection..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JellyfinClient candidate = new JellyfinClient(url, apiKey, "");
            Exception failure = null;
            try {
                candidate.testConnection();
            } catch (Exception e) {
                failure = e;
            }
            Exception finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalFailure == null) {
                    plugin.jellyfinRegistry().setPersonalServer(p, url, apiKey, "");
                    p.sendMessage(info("Connected! You're now using your own Jellyfin server for music."));
                } else {
                    p.sendMessage(error("Couldn't connect: " + finalFailure.getMessage()
                            + ". Check the URL and API key, then run /music myserver again."));
                }
            });
        });
    }

    // ---- shared controls ----

    private void control(CommandSender sender, PlaybackSession session, String action, String[] args) {
        if (session == null) {
            sender.sendMessage(error("Nothing is set up to control yet."));
            return;
        }
        switch (action) {
            case "pause" -> {
                session.setPaused(true);
                sender.sendMessage(info("Paused."));
            }
            case "resume" -> {
                session.setPaused(false);
                sender.sendMessage(info("Resumed."));
            }
            case "stop" -> {
                session.stopAll();
                sender.sendMessage(info("Stopped."));
            }
            case "skip" -> {
                session.skip();
                sender.sendMessage(info("Skipped."));
            }
            case "volume" -> {
                if (args.length == 0) {
                    sender.sendMessage(info("Volume: " + Math.round(session.volume() * 100) + "%"));
                    return;
                }
                try {
                    int pct = Integer.parseInt(args[0]);
                    session.setVolume(pct / 100f);
                    sender.sendMessage(info("Volume set to " + Math.max(0, Math.min(200, pct)) + "%"));
                } catch (NumberFormatException e) {
                    sender.sendMessage(error("Volume must be a number 0-200."));
                }
            }
            default -> sender.sendMessage(error("Unknown action: " + action));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("jellyfinvc.admin")) {
            sender.sendMessage(error("You don't have permission to do that."));
            return;
        }
        plugin.reload();
        sender.sendMessage(info("Config reloaded."));
    }

    // ---- helpers ----

    private void personal(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(error("Only players can use that."));
            return;
        }
        if (!p.hasPermission("jellyfinvc.use")) {
            p.sendMessage(error("You don't have permission to use this."));
            return;
        }
        action.accept(p);
    }

    private void searchAndAct(Player p, String query, int limit, java.util.function.Consumer<List<JellyfinTrack>> onResult) {
        JellyfinClient client = clientFor(p);
        if (!requireConfigured(p, client)) {
            return;
        }
        runAsync(() -> {
            try {
                return client.search(query, limit);
            } catch (Exception e) {
                plugin.getLogger().warning("Jellyfin search failed: " + e.getMessage());
                return List.<JellyfinTrack>of();
            }
        }, onResult);
    }

    private void searchAndActSender(CommandSender sender, String query, java.util.function.Consumer<List<JellyfinTrack>> onResult) {
        JellyfinClient client = clientForSender(sender);
        if (!client.isConfigured()) {
            sender.sendMessage(error(notConfiguredMessage(sender)));
            return;
        }
        runAsync(() -> {
            try {
                return client.search(query, 1);
            } catch (Exception e) {
                plugin.getLogger().warning("Jellyfin search failed: " + e.getMessage());
                return List.<JellyfinTrack>of();
            }
        }, onResult);
    }

    private JellyfinClient clientFor(Player p) {
        return plugin.jellyfinRegistry().clientFor(p);
    }

    private JellyfinClient clientForSender(CommandSender sender) {
        if (sender instanceof Player p) {
            return plugin.jellyfinRegistry().clientFor(p);
        }
        return plugin.jellyfinRegistry().defaultClient();
    }

    /** Returns true if usable; otherwise sends an explanatory error and returns false. */
    private boolean requireConfigured(Player p, JellyfinClient client) {
        if (client.isConfigured()) {
            return true;
        }
        p.sendMessage(error(notConfiguredMessage(p)));
        return false;
    }

    private String notConfiguredMessage(CommandSender sender) {
        boolean hasPersonal = sender instanceof Player p && plugin.jellyfinRegistry().hasPersonalServer(p);
        if (hasPersonal) {
            return "Your personal Jellyfin server isn't reachable right now.";
        }
        return "No Jellyfin server is set up yet. Connect your own with /music myserver, "
                + "or ask a server admin to configure the shared one in config.yml.";
    }

    private <T> void runAsync(Supplier<T> work, java.util.function.Consumer<T> onMainThread) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = work.get();
            Bukkit.getScheduler().runTask(plugin, () -> onMainThread.accept(result));
        });
    }

    private static String[] tail(String[] args, int from) {
        String[] out = new String[Math.max(0, args.length - from)];
        System.arraycopy(args, from, out, 0, out.length);
        return out;
    }

    private Component info(String text) {
        return Component.text(text, NamedTextColor.AQUA);
    }

    private Component error(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    private void sendHelp(CommandSender sender) {
        List<String> lines = List.of(
                "/music - open the browse/search/playlist menu",
                "/music play <song> - play a track for just you",
                "/music search <song> - browse results in a GUI",
                "/music queue <song> - add a track to your queue",
                "/music playlist <name> - play a Jellyfin playlist",
                "/music pause|resume|stop|skip - control your playback",
                "/music volume <0-200> - set your playback volume",
                "/music jukebox place|menu|play|search|stop|list - a shared jukebox at your location",
                "/music group [menu|play|search|pause|resume|stop|skip|volume] - play into your voice chat group",
                "/music broadcast [menu|play|search|...] - server-wide (requires permission)",
                "/music broadcast off|on - stop/resume hearing server-wide broadcasts (anyone can use this)",
                "/music broadcast enable|disable - turn the whole broadcast feature on/off server-wide (admin only)",
                "/music mute|unmute - silence/restore all Jellyfin audio for you (personal/group/broadcast/jukebox)",
                "/music myserver [clear|status] - connect (or remove) your own personal Jellyfin server",
                "/music albumradio off|on - toggle auto-playing another album when one ends (on by default)"
        );
        for (String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("play", "search", "queue", "playlist", "pause", "resume", "stop", "skip",
                    "volume", "gui", "menu", "nowplaying", "jukebox", "group", "broadcast", "mute", "unmute",
                    "myserver", "albumradio", "reload"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("jukebox")) {
                return filter(List.of("place", "menu", "stop", "list", "play", "search"), args[1]);
            }
            if (sub.equals("group")) {
                return filter(List.of("menu", "play", "search", "pause", "resume", "stop", "skip", "volume"), args[1]);
            }
            if (sub.equals("broadcast")) {
                return filter(List.of("menu", "play", "search", "pause", "resume", "stop", "skip", "volume",
                        "off", "on", "enable", "disable"), args[1]);
            }
            if (sub.equals("myserver")) {
                return filter(List.of("clear", "status"), args[1]);
            }
            if (sub.equals("albumradio")) {
                return filter(List.of("off", "on"), args[1]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.startsWith(lower)).collect(Collectors.toList());
    }
}
