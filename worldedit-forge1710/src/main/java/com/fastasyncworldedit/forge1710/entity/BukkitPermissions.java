package com.fastasyncworldedit.forge1710.entity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Permission checks through Bukkit when the server is a Forge + Bukkit hybrid such as Crucible, so permission plugins
 * (LuckPerms, PermissionsEx, ...) can grant WorldEdit permissions to players who are not operators. Bukkit is reached
 * reflectively; on plain Forge (and in single player) every call returns {@code null}.
 */
final class BukkitPermissions {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");
    private static final MethodHandle GET_PLAYER;
    private static final MethodHandle HAS_PERMISSION;

    static {
        MethodHandle getPlayer = null;
        MethodHandle hasPermission = null;
        try {
            ClassLoader loader = BukkitPermissions.class.getClassLoader();
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", false, loader);
            Class<?> bukkitPlayer = Class.forName("org.bukkit.entity.Player", false, loader);
            Class<?> permissible = Class.forName("org.bukkit.permissions.Permissible", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            getPlayer = lookup.findStatic(bukkit, "getPlayer", MethodType.methodType(bukkitPlayer, UUID.class));
            hasPermission = lookup.findVirtual(permissible, "hasPermission", MethodType.methodType(boolean.class, String.class));
            LOGGER.info("Bukkit found: WorldEdit permissions are checked through Bukkit permission plugins");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Plain Forge.
        }
        GET_PLAYER = getPlayer;
        HAS_PERMISSION = hasPermission;
    }

    private BukkitPermissions() {
    }

    /**
     * Whether Bukkit grants the permission, or {@code null} if Bukkit is absent or does not know the player (yet).
     */
    @Nullable
    static Boolean check(UUID uuid, String permission) {
        if (GET_PLAYER == null) {
            return null;
        }
        try {
            Object player = GET_PLAYER.invoke(uuid);
            return player == null ? null : (boolean) HAS_PERMISSION.invoke(player, permission);
        } catch (Throwable t) {
            return null;
        }
    }

}
