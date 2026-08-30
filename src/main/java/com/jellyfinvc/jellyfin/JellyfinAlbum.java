package com.jellyfinvc.jellyfin;

/** A Jellyfin music album, as shown when browsing (not yet loaded with its tracks). */
public record JellyfinAlbum(JellyfinClient client, String id, String name, String artist, int trackCount) {

    public String display() {
        if (artist == null || artist.isBlank()) {
            return name;
        }
        return name + " - " + artist;
    }
}
