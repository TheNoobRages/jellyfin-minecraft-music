package com.jellyfinvc.audio;

import org.bukkit.Location;

import java.util.UUID;

/** Metadata about a placed jukebox, for listing/targeting it later. */
public record JukeboxInfo(UUID id, UUID ownerUuid, String ownerName, Location location) {

    public String sessionKey() {
        return "jukebox:" + id;
    }
}
