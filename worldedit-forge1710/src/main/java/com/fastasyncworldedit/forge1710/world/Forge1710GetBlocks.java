package com.fastasyncworldedit.forge1710.world;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.fastasyncworldedit.forge1710.registry.NativeBlockMapper;
import com.fastasyncworldedit.core.util.NbtUtils;
import com.fastasyncworldedit.forge1710.entity.Forge1710Entity;
import com.fastasyncworldedit.forge1710.internal.NativeData;
import com.fastasyncworldedit.forge1710.registry.Forge1710Biomes;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import net.minecraft.block.Block;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Chunk reader/writer for 1.7.10.
 *
 * <p>Reads go through {@link ExtendedBlockStorage}'s block/metadata accessors, which EndlessIDs redirects to its
 * extended storage, so extended block ids work without touching the raw arrays. Writes are applied on the server
 * thread with {@code World.setBlock}; this is the correct-but-slow path (milestone M1).</p>
 */
public class Forge1710GetBlocks extends CharGetBlocks {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");
    private static final int SECTIONS = 16;

    private final Forge1710World world;
    private final int chunkX;
    private final int chunkZ;
    private final ReentrantLock callLock = new ReentrantLock();
    private final ConcurrentHashMap<Integer, IChunkGet> copies = new ConcurrentHashMap<>();
    private boolean createCopy;
    private int copyKey;

    static final boolean DEBUG = Boolean.getBoolean("fawe.forge1710.debugQueue");

    public Forge1710GetBlocks(Forge1710World world, int chunkX, int chunkZ) {
        super(0, SECTIONS - 1);
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        if (DEBUG) {
            LOGGER.info("[QDEBUG] new get {} chunk {},{} thread {}", System.identityHashCode(this), chunkX, chunkZ,
                    Thread.currentThread().getName());
        }
    }

    // Readers only live for one edit (see Forge1710QueueHandler), so keeping the chunk avoids a server-thread round
    // trip for every hasSection/update call.
    private volatile Chunk chunk;
    private volatile Map<BlockVector3, FaweCompoundTag> tileCache;
    private volatile int[] nativeBiomes;

    private Chunk chunk() {
        Chunk local = chunk;
        if (local == null) {
            local = world.getChunk(chunkX, chunkZ);
            chunk = local;
        }
        return local;
    }

    /**
     * FAWE only loads sections that report existing data; the inherited implementation only knows about sections that
     * were already loaded, so every unloaded section read as air.
     */
    @Override
    public boolean hasSection(int layer) {
        boolean result = layer >= 0 && layer < SECTIONS && chunk().getBlockStorageArray()[layer] != null;
        if (DEBUG && !result) {
            LOGGER.info("[QDEBUG] hasSection false get {} chunk {},{} layer {} chunkClass {} thread {}",
                    System.identityHashCode(this), chunkX, chunkZ, layer, chunk().getClass().getSimpleName(),
                    Thread.currentThread().getName());
        }
        return result;
    }

    @Override
    public boolean hasNonEmptySection(int layer) {
        return hasSection(layer);
    }

    @Override
    public char[] update(int layer, char[] data, boolean aggressive) {
        if (data == null) {
            data = new char[4096];
        }
        readSection(chunk(), layer, data);
        if (DEBUG) {
            LOGGER.info("[QDEBUG] read get {} chunk {},{} layer {} first={} thread {}", System.identityHashCode(this), chunkX,
                    chunkZ, layer, (int) data[0], Thread.currentThread().getName());
        }
        return data;
    }

    static void readSection(Chunk chunk, int layer, char[] data) {
        ExtendedBlockStorage storage = layer >= 0 && layer < SECTIONS ? chunk.getBlockStorageArray()[layer] : null;
        if (storage == null) {
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
            return;
        }
        NativeBlockMapper mapper = NativeBlockMapper.get();
        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = storage.getBlockByExtId(x, y, z);
                    int meta = storage.getExtBlockMetadata(x, y, z);
                    data[index++] = mapper.toOrdinal(Block.getIdFromBlock(block), meta);
                }
            }
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <T extends Future<T>> T call(IQueueExtent<? extends IChunk> owner, IChunkSet set, Runnable finalizer) {
        if (!callLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Attempted to call chunk GET but chunk was not call-locked.");
        }
        final int key = copyKey;
        final boolean copy = createCopy;
        // Supplier (not Runnable): on the main thread QueueHandler runs it inline, and the Runnable overload would return a
        // cancelled future there.
        Supplier<Void> task = () -> {
            try {
                apply(set, key, copy);
            } catch (Throwable t) {
                LOGGER.error("Error applying FAWE changes to chunk {},{}", chunkX, chunkZ, t);
            }
            if (finalizer != null) {
                finalizer.run();
            }
            return null;
        };
        return (T) (Future) Fawe.instance().getQueueHandler().sync(task);
    }

    /**
     * Server thread only.
     */
    private void apply(IChunkSet set, int key, boolean copy) {
        WorldServer nmsWorld = world.getWorld();
        Chunk chunk = nmsWorld.getChunkFromChunkCoords(chunkX, chunkZ);
        Forge1710GetBlocksCopy snapshot = null;
        if (copy) {
            snapshot = new Forge1710GetBlocksCopy(chunkX, chunkZ);
            if (copies.putIfAbsent(key, snapshot) != null) {
                throw new IllegalStateException("Copy key already used.");
            }
        }
        SideEffectSet sideEffects = set.getSideEffectSet();
        boolean neighbors = sideEffects != null && sideEffects.shouldApply(SideEffect.NEIGHBORS);
        int flags = 2 | (neighbors ? 1 : 0);
        NativeBlockMapper mapper = NativeBlockMapper.get();
        int bx = chunkX << 4;
        int bz = chunkZ << 4;

        // Tile entities in positions that get a new block are saved for history and removed, like the Bukkit adapter.
        if (!chunk.chunkTileEntityMap.isEmpty()) {
            for (Object value : new ArrayList<>(chunk.chunkTileEntityMap.values())) {
                TileEntity tile = (TileEntity) value;
                int layer = tile.yCoord >> 4;
                if (layer < 0 || layer >= SECTIONS || !set.hasSection(layer)) {
                    continue;
                }
                char ordinal = set.getBlock(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15).getOrdinalChar();
                if (ordinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    continue;
                }
                if (snapshot != null) {
                    FaweCompoundTag tag = NativeData.tile(tile);
                    if (tag != null) {
                        snapshot.storeTile(BlockVector3.at(tile.xCoord, tile.yCoord, tile.zCoord), tag);
                    }
                }
                nmsWorld.removeTileEntity(tile.xCoord, tile.yCoord, tile.zCoord);
            }
        }

        for (int layer = 0; layer < SECTIONS; layer++) {
            if (!set.hasSection(layer)) {
                continue;
            }
            char[] setArr = set.loadIfPresent(layer);
            if (setArr == null) {
                continue;
            }
            if (snapshot != null) {
                char[] before = new char[4096];
                readSection(chunk, layer, before);
                snapshot.storeSection(layer, before);
            }
            int by = layer << 4;
            for (int index = 0; index < 4096; index++) {
                char ordinal = setArr[index];
                if (ordinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    continue;
                }
                int nativeId = mapper.toNative(ordinal);
                if (nativeId < 0) {
                    continue;
                }
                int x = bx + (index & 15);
                int y = by + (index >> 8);
                int z = bz + ((index >> 4) & 15);
                Block block = Block.getBlockById(nativeId >> 4);
                int meta = nativeId & 15;
                if (chunk.getBlock(x & 15, y, z & 15) == block && chunk.getBlockMetadata(x & 15, y, z & 15) == meta) {
                    continue;
                }
                nmsWorld.setBlock(x, y, z, block, meta, flags);
                // Some blocks pick their own metadata when placed (chests face away from neighbours, rails and
                // stairs re-orient); FAWE must reproduce the exact state, e.g. for undo.
                if (chunk.getBlock(x & 15, y, z & 15) == block && chunk.getBlockMetadata(x & 15, y, z & 15) != meta) {
                    nmsWorld.setBlockMetadataWithNotify(x, y, z, meta, 2);
                }
            }
            synchronized (sectionLocks[layer]) {
                blocks[layer] = null;
            }
        }

        boolean biomesChanged = applyBiomes(set, chunk, snapshot);

        Set<UUID> removes = set.getEntityRemoves();
        if (removes != null && !removes.isEmpty()) {
            Set<UUID> removed = new HashSet<>();
            for (net.minecraft.entity.Entity entity : chunkEntities(chunk)) {
                UUID uuid = entity.getUniqueID();
                if (removes.contains(uuid)) {
                    if (snapshot != null) {
                        FaweCompoundTag tag = NativeData.entity(entity);
                        if (tag != null) {
                            snapshot.storeEntity(tag);
                        }
                    }
                    entity.setDead();
                    removed.add(uuid);
                }
            }
            // Only entities that were really removed belong in history.
            removes.clear();
            removes.addAll(removed);
        }

        Collection<FaweCompoundTag> spawns = set.entities();
        if (spawns != null && !spawns.isEmpty()) {
            Iterator<FaweCompoundTag> iterator = spawns.iterator();
            while (iterator.hasNext()) {
                if (NativeData.spawn(nmsWorld, iterator.next()) == null) {
                    iterator.remove();
                }
            }
        }

        Map<BlockVector3, FaweCompoundTag> tiles = set.tiles();
        if (tiles != null && !tiles.isEmpty()) {
            for (Map.Entry<BlockVector3, FaweCompoundTag> entry : tiles.entrySet()) {
                BlockVector3 pos = entry.getKey();
                NativeData.loadTile(nmsWorld, bx + (pos.x() & 15), pos.y(), bz + (pos.z() & 15), entry.getValue());
            }
        }

        tileCache = null;
        nativeBiomes = null;
        if (DEBUG) {
            LOGGER.info("[QDEBUG] applied get {} chunk {},{}", System.identityHashCode(this), chunkX, chunkZ);
        }
        chunk.setChunkModified();
        if (biomesChanged) {
            Forge1710World.resendChunk(nmsWorld, chunkX, chunkZ);
        }
    }

    /**
     * FAWE biomes are 4x4x4 cells per section; 1.7.10 has one biome per column. The highest cell set in a column wins.
     */
    private boolean applyBiomes(IChunkSet set, Chunk chunk, @Nullable Forge1710GetBlocksCopy snapshot) {
        BiomeType[][] biomes = set.getBiomes();
        if (biomes == null) {
            return false;
        }
        int[] current = Forge1710Biomes.read(chunk);
        int[] updated = current.clone();
        boolean changed = false;
        for (int layer = Math.min(biomes.length, SECTIONS) - 1; layer >= 0; layer--) {
            BiomeType[] cells = biomes[layer];
            if (cells == null) {
                continue;
            }
            for (int cellZ = 0; cellZ < 4; cellZ++) {
                for (int cellX = 0; cellX < 4; cellX++) {
                    BiomeType type = null;
                    for (int cellY = 3; cellY >= 0 && type == null; cellY--) {
                        type = cells[cellY << 4 | cellZ << 2 | cellX];
                    }
                    int nativeId = Forge1710Biomes.toNative(type);
                    if (nativeId < 0) {
                        continue;
                    }
                    for (int z = cellZ << 2; z < (cellZ + 1) << 2; z++) {
                        for (int x = cellX << 2; x < (cellX + 1) << 2; x++) {
                            int index = z << 4 | x;
                            if (updated[index] != nativeId && updated[index] == current[index]) {
                                updated[index] = nativeId;
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        if (changed) {
            if (snapshot != null) {
                snapshot.storeBiomes(current);
            }
            Forge1710Biomes.write(chunk, updated);
        }
        return changed;
    }

    private static List<net.minecraft.entity.Entity> chunkEntities(Chunk chunk) {
        List<net.minecraft.entity.Entity> out = new ArrayList<>();
        for (List<?> list : chunk.entityLists) {
            for (Object o : list) {
                net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) o;
                if (!(entity instanceof EntityPlayer) && !entity.isDead) {
                    out.add(entity);
                }
            }
        }
        return out;
    }

    /**
     * Server thread only.
     */
    public void resendToWatchers() {
        Forge1710World.resendChunk(world.getWorld(), chunkX, chunkZ);
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        int[] local = nativeBiomes;
        if (local == null) {
            local = Forge1710Biomes.read(chunk());
            nativeBiomes = local;
        }
        int id = local[(z & 15) << 4 | (x & 15)];
        if (id < 0) {
            return world.getBiomeType((chunkX << 4) + (x & 15), y, (chunkZ << 4) + (z & 15));
        }
        BiomeType type = Forge1710Biomes.toFawe(id);
        return type != null ? type : BiomeTypes.PLAINS;
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return y < 0 || y > 255 ? 15 : chunk().getSavedLightValue(EnumSkyBlock.Sky, x & 15, y, z & 15);
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        return y < 0 || y > 255 ? 0 : chunk().getSavedLightValue(EnumSkyBlock.Block, x & 15, y, z & 15);
    }

    @Override
    public int[] getHeightMap(HeightMapType type) {
        Chunk chunk = chunk();
        int[] result = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                result[z << 4 | x] = chunk.getHeightValue(x, z);
            }
        }
        return result;
    }

    @Override
    public @Nullable FaweCompoundTag entity(UUID uuid) {
        for (FaweCompoundTag tag : entities()) {
            if (uuid.equals(NbtUtils.uuid(tag))) {
                return tag;
            }
        }
        return null;
    }

    @Override
    public Set<Entity> getFullEntities() {
        return Forge1710World.onMainThread(() -> {
            Set<Entity> out = new HashSet<>();
            for (net.minecraft.entity.Entity entity : chunkEntities(chunk())) {
                out.add(new Forge1710Entity(entity));
            }
            return out;
        });
    }

    @Override
    public boolean isCreateCopy() {
        return createCopy;
    }

    @Override
    public int setCreateCopy(boolean createCopy) {
        if (!callLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Attempting to set if chunk GET should create copy, but it is not call-locked.");
        }
        this.createCopy = createCopy;
        return ++this.copyKey;
    }

    @Override
    public IChunkGet getCopy(int key) {
        return copies.remove(key);
    }

    @Override
    public void lockCall() {
        callLock.lock();
    }

    @Override
    public void unlockCall() {
        callLock.unlock();
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
        Map<BlockVector3, FaweCompoundTag> local = tileCache;
        if (local == null) {
            local = Forge1710World.onMainThread(() -> {
                Chunk chunk = chunk();
                if (chunk.chunkTileEntityMap.isEmpty()) {
                    return Collections.<BlockVector3, FaweCompoundTag>emptyMap();
                }
                Map<BlockVector3, FaweCompoundTag> out = new HashMap<>();
                for (Object value : chunk.chunkTileEntityMap.values()) {
                    TileEntity tile = (TileEntity) value;
                    if (tile.isInvalid()) {
                        continue;
                    }
                    FaweCompoundTag tag = NativeData.tile(tile);
                    if (tag != null) {
                        out.put(BlockVector3.at(tile.xCoord, tile.yCoord, tile.zCoord), tag);
                    }
                }
                return out;
            });
            tileCache = local;
        }
        return local;
    }

    @Override
    public @Nullable FaweCompoundTag tile(int x, int y, int z) {
        Map<BlockVector3, FaweCompoundTag> local = tiles();
        if (local.isEmpty()) {
            return null;
        }
        return local.get(BlockVector3.at((chunkX << 4) + (x & 15), y, (chunkZ << 4) + (z & 15)));
    }

    @Override
    public Collection<FaweCompoundTag> entities() {
        return Forge1710World.onMainThread(() -> {
            List<FaweCompoundTag> out = new ArrayList<>();
            for (net.minecraft.entity.Entity entity : chunkEntities(chunk())) {
                FaweCompoundTag tag = NativeData.entity(entity);
                if (tag != null) {
                    out.add(tag);
                }
            }
            return out;
        });
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
