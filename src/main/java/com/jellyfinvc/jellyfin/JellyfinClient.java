package com.jellyfinvc.jellyfin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Talks to one Jellyfin server: searching the library, resolving playlists,
 * and building the URL used to pull a track as a raw PCM stream for the voice
 * chat audio pipeline.
 *
 * <p>Each player can point this at a different server (their own Jellyfin, or
 * the shared default one the server operator configured), so instances are
 * cheap value-ish objects rather than a single plugin-wide singleton. Every
 * {@link JellyfinTrack}/{@link JellyfinPlaylist}/{@link JellyfinAlbum} this
 * client returns carries a reference back to it, so a queue can freely mix
 * tracks sourced from different players' servers and each still streams from
 * the right place.
 */
public final class JellyfinClient {

    private static final HttpClient SHARED_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String serverUrl;
    private final String apiKey;
    private final String userId;

    public JellyfinClient(String serverUrl, String apiKey, String userId) {
        this.serverUrl = stripTrailingSlash(serverUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.userId = userId == null ? "" : userId;
    }

    public static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public boolean isConfigured() {
        return !serverUrl.isBlank() && !apiKey.isBlank();
    }

    /** Hits an authenticated endpoint to confirm the URL/key actually work, before saving them anywhere. */
    public void testConnection() throws IOException, InterruptedException {
        get(serverUrl + "/System/Info");
    }

    public List<JellyfinTrack> search(String query, int limit) throws IOException, InterruptedException {
        String url = serverUrl + "/Items"
                + "?SearchTerm=" + encode(query)
                + "&IncludeItemTypes=Audio"
                + "&Recursive=true"
                + "&Limit=" + limit
                + "&SortBy=SortName";
        return parseTracks(get(url));
    }

    public String resolvePlaylistId(String name) throws IOException, InterruptedException {
        String url = serverUrl + "/Items"
                + "?SearchTerm=" + encode(name)
                + "&IncludeItemTypes=Playlist"
                + "&Recursive=true"
                + "&Limit=1";
        String body = get(url);
        Map<String, Object> root = Json.parseObject(body);
        List<Object> items = Json.asList(root.get("Items"));
        if (items.isEmpty()) {
            return null;
        }
        return Json.asString(Json.asMap(items.get(0)).get("Id"), null);
    }

    public List<JellyfinTrack> getPlaylistItems(String playlistId) throws IOException, InterruptedException {
        // The dedicated /Playlists/{id}/Items route needs a userId query param
        // to resolve correctly on some Jellyfin versions; ParentId+Recursive on
        // the general /Items endpoint returns the same tracks without one.
        String url = serverUrl + "/Items"
                + "?ParentId=" + encode(playlistId)
                + "&Recursive=true";
        return parseTracks(get(url));
    }

    public List<JellyfinTrack> getAlbumItems(String albumId) throws IOException, InterruptedException {
        String url = serverUrl + "/Items"
                + "?ParentId=" + encode(albumId)
                + "&Recursive=true"
                + "&SortBy=IndexNumber";
        return parseTracks(get(url));
    }

    public List<JellyfinAlbum> listAlbums() throws IOException, InterruptedException {
        String url = serverUrl + "/Items"
                + "?IncludeItemTypes=MusicAlbum"
                + "&Recursive=true"
                + "&SortBy=SortName"
                + "&Limit=200";
        Map<String, Object> root = Json.parseObject(get(url));
        List<Object> items = Json.asList(root.get("Items"));
        List<JellyfinAlbum> albums = new ArrayList<>(items.size());
        for (Object raw : items) {
            Map<String, Object> item = Json.asMap(raw);
            String id = Json.asString(item.get("Id"), null);
            if (id == null) {
                continue;
            }
            String name = Json.asString(item.get("Name"), "Untitled album");
            String artist = Json.asString(item.get("AlbumArtist"), "");
            int childCount = (int) Json.asLong(item.get("ChildCount"), 0L);
            albums.add(new JellyfinAlbum(this, id, name, artist, childCount));
        }
        return albums;
    }

    public List<JellyfinPlaylist> listPlaylists() throws IOException, InterruptedException {
        String url = serverUrl + "/Items"
                + "?IncludeItemTypes=Playlist"
                + "&Recursive=true"
                + "&SortBy=SortName"
                + "&Limit=100";
        Map<String, Object> root = Json.parseObject(get(url));
        List<Object> items = Json.asList(root.get("Items"));
        List<JellyfinPlaylist> playlists = new ArrayList<>(items.size());
        for (Object raw : items) {
            Map<String, Object> item = Json.asMap(raw);
            String id = Json.asString(item.get("Id"), null);
            if (id == null) {
                continue;
            }
            String name = Json.asString(item.get("Name"), "Untitled playlist");
            int childCount = (int) Json.asLong(item.get("ChildCount"), 0L);
            playlists.add(new JellyfinPlaylist(this, id, name, childCount));
        }
        return playlists;
    }

    /**
     * The URL used to stream a track back as Ogg/Opus, letting Jellyfin's own
     * ffmpeg backend do the transcoding. Raw PCM/WAV isn't used here because
     * several Jellyfin deployments 500 on any container that needs an
     * up-front known length for a live transcode (wav/matroska/webm/ts all
     * fail the same way) - Opus/Ogg has no such requirement. The plugin
     * decodes the Opus packets itself via Simple Voice Chat's own decoder.
     */
    public String buildOpusStreamUrl(String itemId) {
        return serverUrl + "/Audio/" + itemId + "/stream.opus"
                + "?AudioCodec=opus"
                + "&Container=opus"
                + "&AudioSampleRate=48000"
                + "&AudioChannels=1"
                + (userId.isBlank() ? "" : "&UserId=" + encode(userId))
                + "&api_key=" + encode(apiKey);
    }

    private List<JellyfinTrack> parseTracks(String body) {
        Map<String, Object> root = Json.parseObject(body);
        List<Object> items = Json.asList(root.get("Items"));
        List<JellyfinTrack> tracks = new ArrayList<>(items.size());
        for (Object raw : items) {
            Map<String, Object> item = Json.asMap(raw);
            String id = Json.asString(item.get("Id"), null);
            if (id == null) {
                continue;
            }
            String name = Json.asString(item.get("Name"), "Unknown Track");
            String artist = joinArtists(item);
            String album = Json.asString(item.get("Album"), "");
            String albumId = Json.asString(item.get("AlbumId"), null);
            long ticks = Json.asLong(item.get("RunTimeTicks"), 0L);
            tracks.add(new JellyfinTrack(this, id, name, artist, album, albumId, ticks));
        }
        return tracks;
    }

    private String joinArtists(Map<String, Object> item) {
        Object artists = item.get("Artists");
        if (artists instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object a : list) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(a);
            }
            return sb.toString();
        }
        return Json.asString(item.get("AlbumArtist"), "");
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Emby-Token", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = SHARED_HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Jellyfin returned HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public HttpClient httpClient() {
        return SHARED_HTTP;
    }
}
