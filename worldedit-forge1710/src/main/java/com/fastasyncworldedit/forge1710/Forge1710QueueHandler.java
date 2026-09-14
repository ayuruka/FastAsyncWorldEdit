package com.fastasyncworldedit.forge1710;

import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import com.sk89q.worldedit.world.World;

/**
 * 1.7.10 has no async catcher or timings to toggle while FAWE edits chunks.
 */
public class Forge1710QueueHandler extends QueueHandler {

    @Override
    public void startUnsafe(boolean parallel) {
    }

    @Override
    public void endUnsafe(boolean parallel) {
    }

    /**
     * No cross-edit chunk cache. The default cache keeps chunk readers (and the section arrays they loaded) until the
     * garbage collector clears its weak references. Newer platforms notice changes because Minecraft replaces section
     * objects, but 1.7.10 writes into the same arrays, so a cached reader kept returning the blocks from before the
     * previous edit (//set stone followed by //count stone counted 0). Each edit gets fresh readers instead; within one
     * edit the queue still keeps its own chunks.
     */
    @Override
    public IChunkCache<IChunkGet> getOrCreateWorldCache(World world) {
        World unwrapped = WorldWrapper.unwrap(world);
        return unwrapped::get;
    }

}
