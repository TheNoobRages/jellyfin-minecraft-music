# JellyfinVoiceChat

A Paper plugin that streams music from a Jellyfin server into Minecraft through
[Simple Voice Chat](https://modrepo.de/minecraft/voicechat), for Paper 26.2.

Jellyfin transcodes each track server-side to Ogg/Opus, and the plugin decodes
that itself using Simple Voice Chat's own Opus decoder before feeding it into
the audio API 20ms at a time - no third-party audio codec library needed.
(Raw PCM/WAV transcoding is asked for on some Jellyfin deployments, but many
servers 500 on that - a live transcode can't give WAV the fixed length it
needs - so Opus, which streams fine, is used instead.)

Personal/group/broadcast audio uses `StaticAudioChannel`. Besides the
documented `setFilter(Predicate<ServerPlayer>)` recipient filter, this plugin
also explicitly calls `addTarget(VoicechatConnection)` for every eligible
player - in testing, `setFilter` alone produced a channel that looked correct
server-side (real decoded audio, continuously delivered, zero errors) but was
completely inaudible client-side; adding explicit targets fixed it. Because a
`VoicechatConnection` reference goes stale across a reconnect, the plugin
listens to Simple Voice Chat's own `PlayerConnectedEvent`/`JoinGroupEvent`/
`LeaveGroupEvent` to re-register (or remove) targets the moment connection or
group membership actually changes, rather than guessing with a fixed delay.

## Using it in-game

Run `/music` with no arguments to open the main menu: **Search Library**
(type a song/artist/album name in chat when prompted - you don't need to know
an exact title, just enough to search) and **Browse Playlists** (lists your
Jellyfin playlists; click one to browse its tracks, shift-click to play the
whole thing). In any track list, click a track to play it now, or shift-click
to add it to the queue - both are always available side by side. The bottom
row of every menu has pause/resume, skip, stop, and volume controls.

## Continuous playback

Pick a single song that belongs to an album (from search, a playlist, or
Browse Albums), and once it ends with nothing else queued, the rest of that
album plays automatically in track order - picking up right after the song
you chose, not from the top. This always happens; it's just what "nothing
queued after this song" naturally falls back to.

Play a *whole* album instead (Browse Albums, idle-click or shift-click), and
once you reach the actual end of it, **album radio** takes over: a different
random album from the same Jellyfin server starts automatically, indefinitely,
until you play something else or stop. This is on by default; turn it off for
yourself with `/music albumradio off` (`on` to turn it back on, no args to
check the current setting). A single song's own "rest of the album" playback
will also spill into album radio at the true end of that album, if you have
it enabled.

## Playback modes

- **Personal** (`/music` menu, or `/music play`) - only you hear it, from
  anywhere on the server.
- **Jukebox** (`/music jukebox place`) - a fixed point in the world; anyone
  nearby hears it, fading out with distance (default 48 blocks).
- **Group** (`/music group`) - only the members of your current Simple Voice
  Chat group hear it, no one else on the server. Membership is live: joining
  the group starts you hearing it immediately, leaving stops it immediately -
  no need to reconnect or restart playback.
- **Broadcast** (`/music broadcast`) - everyone connected to voice chat hears
  it, regardless of location, starting at a low default volume (20%) since it
  plays for the whole server at once. Requires the `jellyfinvc.broadcast`
  permission (op by default) to start/control; anyone can opt out for
  themselves with `/music broadcast off` (and back in with `/music broadcast
  on`), no permission needed. An admin can also disable the feature entirely
  with `/music broadcast disable` (`jellyfinvc.admin`) - this immediately
  stops anything currently broadcasting and blocks anyone (even permission
  holders) from starting a new one until `/music broadcast enable`. This is
  saved to config.yml, so it survives a restart.

Every `/music` menu also has a **Mute All** button (and `/music mute` /
`/music unmute`) that silences personal, group, broadcast, and jukebox audio
for just that player all at once, independent of the broadcast opt-out above.

## Personal Jellyfin servers

The server operator's Jellyfin (configured in `config.yml`) is the shared
default everyone uses automatically. Any player can instead connect their own
Jellyfin server with `/music myserver` - it prompts for a server URL, then an
API key (both typed in chat, never broadcast or echoed back), tests the
connection, and switches that player over to it for search/playlists/albums/
playback. `/music myserver status` shows what's active; `/music myserver
clear` reverts to the shared server. Requires the `jellyfinvc.myserver`
permission (true by default).

This is genuinely per-player, all the way down to actual playback: every
track/playlist/album remembers which server it came from, so a shared queue
(a jukebox, a group, a broadcast) can mix tracks different players added from
their own separate Jellyfin libraries and each still streams from the right
place. Credentials are stored in plaintext in
`plugins/JellyfinVoiceChat/player-servers.yml`, same as the shared API key in
`config.yml` - keep that file private too. Since the server makes outbound
requests to whatever URL a player sets, only grant `jellyfinvc.myserver` to
players you trust not to point it at something malicious internal to your
network.

## Setup

### 1. Get a Jellyfin API key

In Jellyfin: **Dashboard → API Keys → +** (the plus button), give it a name
like "Minecraft", and copy the key it generates.

### 2. Build the plugin

```bash
./gradlew build
```

The compiled plugin will be at `build/libs/JellyfinVoiceChat-1.0.0.jar`.
(First build downloads a JDK 25 toolchain automatically via Gradle - no need
to install Java 25 yourself.)

### 3. Install it

Copy the jar into your server's `plugins/` folder and start the server once
so it generates `plugins/JellyfinVoiceChat/config.yml`.

**Important:** this plugin needs `voicechat-bukkit` built for Paper 26.2 (2.6.21
or newer confirmed working) - update it first if your server is on an older
build, from [Modrinth](https://modrinth.com/plugin/simple-voice-chat/versions)
or [CurseForge](https://www.curseforge.com/minecraft/bukkit-plugins/simple-voice-chat/files),
or this plugin will disable itself on startup.

### 4. Configure

Edit `plugins/JellyfinVoiceChat/config.yml`:

```yaml
jellyfin:
  server-url: "http://your-jellyfin-host:8096"
  api-key: "paste-your-api-key-here"
```

Then run `/music reload` in-game (or restart the server).

## Commands

| Command | Effect |
|---|---|
| `/music` | Open the search/playlist/control menu |
| `/music play <song>` | Play a track, just for you |
| `/music search <song>` | Browse results in a GUI and click to play/queue |
| `/music queue <song>` | Add a track to your personal queue |
| `/music playlist <name>` | Play a whole Jellyfin playlist |
| `/music pause` / `resume` / `stop` / `skip` | Control your personal playback |
| `/music volume <0-200>` | Set your personal playback volume (200 = 2x boost) |
| `/music jukebox place` | Place a jukebox at your current location |
| `/music jukebox menu` | Open the menu scoped to your jukebox |
| `/music jukebox play <song>` / `search <song>` | Play or browse into your jukebox |
| `/music jukebox stop` / `list` | Stop or list your jukeboxes |
| `/music group` | Open the menu scoped to your voice chat group |
| `/music group play\|search\|pause\|resume\|stop\|skip\|volume` | Control group playback |
| `/music broadcast` | Open the server-wide menu (needs permission) |
| `/music broadcast play\|search\|...` | Play server-wide (needs permission) |
| `/music broadcast off` / `on` | Stop/resume hearing broadcasts, for yourself (no permission needed) |
| `/music broadcast disable` / `enable` | Turn the whole broadcast feature off/on server-wide (admin only) |
| `/music mute` / `unmute` | Silence/restore *all* Jellyfin audio for you at once |
| `/music myserver` | Connect your own personal Jellyfin server |
| `/music myserver status` / `clear` | Check or remove your personal server |
| `/music albumradio off` / `on` | Toggle auto-playing another album when one ends (on by default) |
| `/music reload` | Reload config.yml (needs permission) |

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `jellyfinvc.use` | true | Personal playback commands |
| `jellyfinvc.jukebox` | true | Placing/controlling jukeboxes |
| `jellyfinvc.group` | true | Group playback |
| `jellyfinvc.broadcast` | op | Server-wide broadcast |
| `jellyfinvc.myserver` | true | Connecting a personal Jellyfin server |
| `jellyfinvc.admin` | op | `/music reload`, `/music broadcast enable\|disable` |

## Project layout

```
src/main/java/com/jellyfinvc/
  JellyfinVoiceChatPlugin.java   main plugin class
  SvcHook.java                   Simple Voice Chat plugin registration
  config/PluginConfig.java       config.yml loader
  jellyfin/                      Jellyfin HTTP client, JSON parsing, track/playlist DTOs
  audio/                         Ogg/Opus streaming+decoding, playback sessions, jukebox/queue state
  commands/MusicCommand.java     /music command tree
  gui/                           main menu, search results, playlist browser, chat search prompt
```
