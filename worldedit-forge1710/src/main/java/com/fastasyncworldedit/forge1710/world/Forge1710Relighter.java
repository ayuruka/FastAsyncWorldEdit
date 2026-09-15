package com.fastasyncworldedit.forge1710.world;

import com.fastasyncworldedit.core.extent.processor.lighting.Relighter;
import com.sk89q.worldedit.world.World;
import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Relighter for //fixlighting and //removelighting.
 *
 * <p>Edits light the blocks they change themselves (see {@code Forge1710GetBlocks#apply}), so the chunks FAWE's relight
 * processor adds during edits (with per-section skip reasons) are ignored. Whole chunks added without skip reasons come
 * from {@code FaweAPI.fixLighting}; they are relit with vanilla's own chunk light population on the server thread.</p>
 */
public class Forge1710Relighter implements Relighter {

    private final ReentrantLock lock = new ReentrantLock();
    private final World world;
    private final Set<Long> chunks = new LinkedHashSet<>();

    public Forge1710Relighter(World world) {
        this.world = world;
    }

    @Override
    public synchronized boolean addChunk(int cx, int cz, byte[] skipReason, int bitmask) {
        if (skipReason != null || bitmask != 0xFFFF) {
            return false;
        }
        return chunks.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
    }

    @Override
    public void addLightUpdate(int x, int y, int z) {
    }

    @Override
    public void fixLightingSafe(boolean sky) {
        relight(false);
    }

    @Override
    public void removeAndRelight(boolean sky) {
        relight(true);
    }

    @Override
    public void clear() {
        synchronized (this) {
            chunks.clear();
        }
    }

    @Override
    public void removeLighting() {
        forEachChunk((nms, chunk) -> clearLight(nms, chunk));
    }

    @Override
    public void fixBlockLighting() {
        relight(false);
    }

    @Override
    public void fixSkyLighting() {
        relight(false);
    }

    @Override
    public synchronized boolean isEmpty() {
        return chunks.isEmpty();
    }

    @Override
    public ReentrantLock getLock() {
        return lock;
    }

    @Override
    public synchronized boolean isFinished() {
        return chunks.isEmpty();
    }

    @Override
    public void close() {
        clear();
    }

    private void relight(boolean removeFirst) {
        forEachChunk((nms, chunk) -> {
            if (removeFirst) {
                clearLight(nms, chunk);
            }
            chunk.generateSkylightMap();
            chunk.isLightPopulated = false;
            // Sky light below overhangs and every light source; if neighbours are missing, the chunk tick retries.
            chunk.func_150809_p();
            chunk.setChunkModified();
            Forge1710World.resendChunk(nms, chunk.xPosition, chunk.zPosition);
        });
        clear();
    }

    private static void clearLight(WorldServer nms, Chunk chunk) {
        for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
            if (storage == null) {
                continue;
            }
            storage.setBlocklightArray(new NibbleArray(4096, 4));
            if (!nms.provider.hasNoSky) {
                storage.setSkylightArray(new NibbleArray(4096, 4));
            }
        }
        chunk.setChunkModified();
    }

    private interface ChunkAction {

        void apply(WorldServer world, Chunk chunk);

    }

    private void forEachChunk(ChunkAction action) {
        long[] keys;
        synchronized (this) {
            keys = chunks.stream().mapToLong(Long::longValue).toArray();
        }
        if (keys.length == 0 || !(WorldWrapper.unwrap(world) instanceof Forge1710World forgeWorld)) {
            return;
        }
        Forge1710World.onMainThread(() -> {
            WorldServer nms = forgeWorld.getWorld();
            for (long key : keys) {
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (nms.theChunkProviderServer.chunkExists(cx, cz)) {
                    action.apply(nms, nms.getChunkFromChunkCoords(cx, cz));
                }
            }
            return null;
        });
    }

}
