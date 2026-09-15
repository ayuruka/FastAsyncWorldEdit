package com.fastasyncworldedit.forge1710.registry;

import com.sk89q.worldedit.world.entity.EntityType;
import net.minecraft.entity.EntityList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps 1.7.10 entity names ({@link EntityList#stringToClassMapping}: "Zombie", "RTM.Train" ...) to namespaced FAWE entity
 * type ids ("minecraft:zombie", "rtm:train").
 */
public final class Forge1710EntityTypes {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");
    private static final Map<String, String> NATIVE_TO_FAWE = new HashMap<>();
    private static final Map<String, String> FAWE_TO_NATIVE = new HashMap<>();

    private Forge1710EntityTypes() {
    }

    static String faweIdFor(String nativeName) {
        String namespace = "minecraft";
        String path = nativeName;
        int dot = nativeName.indexOf('.');
        if (dot > 0 && dot < nativeName.length() - 1) {
            namespace = nativeName.substring(0, dot);
            path = nativeName.substring(dot + 1);
        }
        return clean(namespace) + ":" + clean(path);
    }

    private static String clean(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]+", "_");
    }

    public static synchronized void registerAll() {
        if (!NATIVE_TO_FAWE.isEmpty()) {
            return;
        }
        for (Object key : EntityList.stringToClassMapping.keySet()) {
            String nativeName = (String) key;
            String id = faweIdFor(nativeName);
            if (FAWE_TO_NATIVE.containsKey(id)) {
                LOGGER.warn("Entity id collision: {} and {} both map to {}", FAWE_TO_NATIVE.get(id), nativeName, id);
                continue;
            }
            NATIVE_TO_FAWE.put(nativeName, id);
            FAWE_TO_NATIVE.put(id, nativeName);
            if (EntityType.REGISTRY.get(id) == null) {
                EntityType.REGISTRY.register(id, new EntityType(id));
            }
        }
        LOGGER.info("Registered {} entity types", NATIVE_TO_FAWE.size());
    }

    @Nullable
    public static String toFawe(@Nullable String nativeName) {
        if (nativeName == null) {
            return null;
        }
        String id = NATIVE_TO_FAWE.get(nativeName);
        return id != null ? id : faweIdFor(nativeName);
    }

    @Nullable
    public static String toNative(@Nullable String faweId) {
        if (faweId == null) {
            return null;
        }
        String name = FAWE_TO_NATIVE.get(faweId.toLowerCase(Locale.ROOT));
        if (name != null) {
            return name;
        }
        // Accept native names directly (e.g. from NBT written by 1.7.10 itself).
        return EntityList.stringToClassMapping.containsKey(faweId) ? faweId : null;
    }

    @Nullable
    public static EntityType type(@Nullable String nativeName) {
        String id = toFawe(nativeName);
        return id == null ? null : EntityType.REGISTRY.get(id);
    }

}
