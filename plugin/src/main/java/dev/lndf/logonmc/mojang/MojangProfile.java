package dev.lndf.logonmc.mojang;

import java.util.UUID;

/**
 * A premium account: its Mojang UUID and the name in its exact capitalization.
 */
public record MojangProfile(UUID id, String name) {
}
