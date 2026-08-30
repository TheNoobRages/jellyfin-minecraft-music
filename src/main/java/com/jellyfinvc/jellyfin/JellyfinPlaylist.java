package com.jellyfinvc.jellyfin;

/** A Jellyfin playlist, as shown when browsing (not yet loaded with its tracks). */
public record JellyfinPlaylist(JellyfinClient client, String id, String name, int trackCount) {
}
