package com.jellyfinvc.jellyfin;

/**
 * A single playable audio item from Jellyfin (a song). Carries the
 * {@link JellyfinClient} it was fetched from, since a track's id is only
 * meaningful against that specific server - important once different players
 * can each point at their own Jellyfin server but still share one queue (a
 * jukebox, a group, a broadcast).
 */
public record JellyfinTrack(JellyfinClient client, String id, String name, String artist, String album, long runTimeTicks) {

    public String durationLabel() {
        long totalSeconds = runTimeTicks / 10_000_000L;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    public String display() {
        if (artist == null || artist.isBlank()) {
            return name;
        }
        return name + " - " + artist;
    }
}
