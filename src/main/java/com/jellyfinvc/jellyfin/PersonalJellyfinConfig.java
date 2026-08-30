package com.jellyfinvc.jellyfin;

/** Raw credentials for one player's own Jellyfin server. */
public record PersonalJellyfinConfig(String serverUrl, String apiKey, String userId) {
}
