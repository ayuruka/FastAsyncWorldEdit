package com.fastasyncworldedit.forge1710.registry;

import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps 1.7.10 biomes (vanilla and modded, from {@link BiomeGenBase#getBiomeGenArray()}) to FAWE biome types and reads or
 * writes a chunk's biome array. EndlessIDs replaces the byte array with a short array (ChunkBiomeHook); that is used
 * reflectively when present so there is no compile-time dependency on EndlessIDs.
 */
public final class Forge1710Biomes {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");
    private static final Map<String, BiomeGenBase> BY_ID = new HashMap<>();
    private static final Map<Integer, BiomeType> BY_NATIVE = new HashMap<>();

    private static final MethodHandle GET_SHORTS;
    private static final MethodHandle SET_SHORTS;

    static {
        MethodHandle get = null;
        MethodHandle set = null;
        try {
            Class<?> hook = Class.forName("com.falsepattern.endlessids.mixin.helpers.ChunkBiomeHook");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            get = lookup.findVirtual(hook, "getBiomeShortArray", MethodType.methodType(short[].class));
            set = lookup.findVirtual(hook, "setBiomeShortArray", MethodType.methodType(void.class, short[].class));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // no EndlessIDs: vanilla byte array
        }
        GET_SHORTS = get;
        SET_SHORTS = set;
    }

    private Forge1710Biomes() {
    }

    public static String id(BiomeGenBase biome) {
        String name = biome.biomeName == null || biome.biomeName.isBlank() ? "biome_" + biome.biomeID : biome.biomeName;
        return "minecraft:" + name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    /**
     * Registers every biome with FAWE. Must run before {@link BiomeTypes} is first used.
     */
    public static synchronized void registerAll() {
        if (!BY_NATIVE.isEmpty()) {
            return;
        }
        for (BiomeGenBase biome : BiomeGenBase.getBiomeGenArray()) {
            if (biome == null) {
                continue;
            }
            String id = id(biome);
            if (BY_ID.containsKey(id)) {
                id = id + "_" + biome.biomeID;
            }
            BiomeType type = BiomeType.REGISTRY.get(id);
            if (type == null) {
                type = BiomeTypes.register(new BiomeType(id));
            }
            type.setLegacyId(biome.biomeID);
            BY_ID.put(id, biome);
            BY_NATIVE.put(biome.biomeID, type);
        }
        LOGGER.info("Registered {} biomes", BY_NATIVE.size());
    }

    @Nullable
    public static BiomeType toFawe(int nativeId) {
        return BY_NATIVE.get(nativeId);
    }

    public static BiomeType toFawe(@Nullable BiomeGenBase biome) {
        BiomeType type = biome == null ? null : BY_NATIVE.get(biome.biomeID);
        return type != null ? type : BiomeTypes.PLAINS;
    }

    /**
     * Native biome id for a FAWE biome, or -1.
     */
    public static int toNative(@Nullable BiomeType type) {
        if (type == null) {
            return -1;
        }
        BiomeGenBase biome = BY_ID.get(type.id());
        return biome == null ? -1 : biome.biomeID;
    }

    /**
     * The chunk's 256 biome ids (index z &lt;&lt; 4 | x); -1 where the biome has not been generated yet.
     */
    public static int[] read(Chunk chunk) {
        int[] out = new int[256];
        if (GET_SHORTS != null) {
            try {
                short[] shorts = (short[]) GET_SHORTS.invoke(chunk);
                for (int i = 0; i < 256; i++) {
                    out[i] = shorts[i];
                }
                return out;
            } catch (Throwable ignored) {
                // fall through to the byte array
            }
        }
        byte[] bytes = chunk.getBiomeArray();
        for (int i = 0; i < 256; i++) {
            int value = bytes[i] & 0xFF;
            out[i] = value == 0xFF ? -1 : value;
        }
        return out;
    }

    public static void write(Chunk chunk, int[] ids) {
        if (SET_SHORTS != null && GET_SHORTS != null) {
            try {
                short[] shorts = (short[]) GET_SHORTS.invoke(chunk);
                for (int i = 0; i < 256; i++) {
                    shorts[i] = (short) ids[i];
                }
                SET_SHORTS.invoke(chunk, shorts);
                chunk.setChunkModified();
                return;
            } catch (Throwable ignored) {
                // fall through to the byte array
            }
        }
        byte[] bytes = chunk.getBiomeArray();
        for (int i = 0; i < 256; i++) {
            bytes[i] = (byte) (ids[i] < 0 ? 0xFF : ids[i]);
        }
        chunk.setBiomeArray(bytes);
        chunk.setChunkModified();
    }

}
