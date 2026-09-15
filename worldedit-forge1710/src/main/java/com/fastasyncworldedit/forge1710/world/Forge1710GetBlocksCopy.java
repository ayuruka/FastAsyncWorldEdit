package com.fastasyncworldedit.forge1710.world;

import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.fastasyncworldedit.core.util.NbtUtils;
import com.fastasyncworldedit.forge1710.registry.Forge1710Biomes;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Snapshot of the sections a chunk had before an edit was applied, used by FAWE history (undo).
 */
public class Forge1710GetBlocksCopy extends CharGetBlocks {

    private final int chunkX;
    private final int chunkZ;
    private final char[][] stored = new char[16][];
    private final Map<BlockVector3, FaweCompoundTag> tiles = new HashMap<>();
    private final List<FaweCompoundTag> entities = new ArrayList<>();
    @Nullable
    private int[] biomes;

    Forge1710GetBlocksCopy(int chunkX, int chunkZ) {
        super(0, 15);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    void storeSection(int layer, char[] data) {
        stored[layer] = data;
    }

    void storeTile(BlockVector3 position, FaweCompoundTag tag) {
        tiles.put(position, tag);
    }

    void storeEntity(FaweCompoundTag tag) {
        entities.add(tag);
    }

    void storeBiomes(int[] nativeBiomes) {
        if (biomes == null) {
            biomes = nativeBiomes.clone();
        }
    }

    @Override
    public boolean hasSection(int layer) {
        return layer >= 0 && layer < 16 && stored[layer] != null;
    }

    @Override
    public char[] update(int layer, char[] data, boolean aggressive) {
        if (data == null) {
            data = new char[4096];
        }
        if (layer >= 0 && layer < 16 && stored[layer] != null) {
            System.arraycopy(stored[layer], 0, data, 0, 4096);
        } else {
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
        }
        return data;
    }

    @Override
    public <T extends Future<T>> T call(IQueueExtent<? extends IChunk> owner, IChunkSet set, Runnable finalize) {
        throw new UnsupportedOperationException("Cannot apply changes to a history copy");
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        if (biomes == null) {
            return BiomeTypes.PLAINS;
        }
        BiomeType type = Forge1710Biomes.toFawe(biomes[(z & 15) << 4 | (x & 15)]);
        return type != null ? type : BiomeTypes.PLAINS;
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return 15;
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        return 0;
    }

    @Override
    public int[] getHeightMap(HeightMapType type) {
        return new int[256];
    }

    @Override
    public @Nullable FaweCompoundTag entity(UUID uuid) {
        for (FaweCompoundTag tag : entities) {
            if (uuid.equals(NbtUtils.uuid(tag))) {
                return tag;
            }
        }
        return null;
    }

    @Override
    public Set<Entity> getFullEntities() {
        return Collections.emptySet();
    }

    @Override
    public boolean isCreateCopy() {
        return false;
    }

    @Override
    public int setCreateCopy(boolean createCopy) {
        return -1;
    }

    @Override
    public void setLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
    }

    @Override
    public void setSkyLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] data) {
    }

    @Override
    public int getMaxY() {
        return 255;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public Map<BlockVector3, FaweCompoundTag> tiles() {
        return tiles;
    }

    @Override
    public @Nullable FaweCompoundTag tile(int x, int y, int z) {
        return tiles.get(BlockVector3.at(x, y, z));
    }

    @Override
    public Collection<FaweCompoundTag> entities() {
        return entities;
    }

    @Override
    public void removeSectionLighting(int layer, boolean sky) {
    }

    @Override
    public int getX() {
        return chunkX;
    }

    @Override
    public int getZ() {
        return chunkZ;
    }

}
