package com.fastasyncworldedit.forge1710.world;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.packet.ChunkPacket;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.forge1710.registry.NativeBlockMapper;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.AbstractWorld;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.generation.TreeType;
import com.fastasyncworldedit.forge1710.Forge1710Adapter;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import com.fastasyncworldedit.forge1710.entity.Forge1710Entity;
import com.fastasyncworldedit.forge1710.internal.NativeData;
import com.fastasyncworldedit.forge1710.registry.Forge1710Biomes;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.regions.Region;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public class Forge1710World extends AbstractWorld {

    private final WeakReference<WorldServer> worldRef;
    private final String name;

    public Forge1710World(WorldServer world) {
        this.worldRef = new WeakReference<>(world);
        this.name = worldName(world);
    }

    public static void registerTreeTypes() {
        Forge1710Trees.registerTypes();
    }

    public static String worldName(WorldServer world) {
        String base = world.getWorldInfo().getWorldName();
        int dimension = world.provider.dimensionId;
        return dimension == 0 ? base : base + "_DIM" + dimension;
    }

    public WorldServer getWorld() {
        WorldServer world = worldRef.get();
        if (world == null) {
            throw new IllegalStateException("World '" + name + "' has been unloaded");
        }
        return world;
    }

    static <T> T onMainThread(Supplier<T> supplier) {
        if (Fawe.isMainThread()) {
            return supplier.get();
        }
        return TaskManager.taskManager().sync(supplier);
    }

    /**
     * Always looked up on the server thread. The chunk provider's loaded-chunk map is not thread safe; a lookup from an
     * edit thread can miss while the server thread changes the map, and then 1.7.10 hands out its shared EmptyChunk,
     * which reads as air (seen as an intermittent "//count stone" = 0 right after "//set stone"). Chunk readers keep the
     * result for the rest of the edit, so this costs one round trip per chunk.
     */
    public Chunk getChunk(int chunkX, int chunkZ) {
        WorldServer world = getWorld();
        return onMainThread(() -> {
            Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
            if (chunk instanceof EmptyChunk) {
                chunk = world.theChunkProviderServer.loadChunk(chunkX, chunkZ);
            }
            return chunk;
        });
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getNameUnsafe() {
        return name;
    }

    // FAWE wraps worlds (WorldWrapper delegates equals to its parent), and LocalSession clears the selection whenever
    // the selector's world is not equal to the command's world. Identity equality made every //pos1 reset the
    // selection, so compare by world name like BukkitWorld does.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof Forge1710World otherWorld) {
            return name.equals(otherWorld.name);
        }
        return other instanceof com.sk89q.worldedit.world.World otherWorld && name.equals(otherWorld.getName());
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String id() {
        return name.replace(" ", "_").toLowerCase(Locale.ROOT);
    }

    @Override
    public Path getStoragePath() {
        WorldServer world = getWorld();
        java.io.File base = world.getSaveHandler().getWorldDirectory();
        String folder = world.provider.getSaveFolder();
        return (folder == null ? base : new java.io.File(base, folder)).toPath();
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getMaxY() {
        return 255;
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return BlockTypes.AIR.getDefaultState();
        }
        Chunk chunk = getChunk(x >> 4, z >> 4);
        Block block = chunk.getBlock(x & 15, y, z & 15);
        int meta = chunk.getBlockMetadata(x & 15, y, z & 15);
        return NativeBlockMapper.get().toState(block, meta);
    }

    @Override
    public BlockState getBlock(BlockVector3 position) {
        return getBlock(position.x(), position.y(), position.z());
    }

    @Override
    public BaseBlock getFullBlock(int x, int y, int z) {
        BlockState state = getBlock(x, y, z);
        if (y < 0 || y > 255) {
            return state.toBaseBlock();
        }
        FaweCompoundTag tag = onMainThread(() -> {
            TileEntity tile = getWorld().getTileEntity(x, y, z);
            return tile == null ? null : NativeData.tile(tile);
        });
        return tag == null ? state.toBaseBlock() : state.toBaseBlock(com.sk89q.worldedit.util.concurrency.LazyReference.computed(tag.linTag()));
    }

    @Override
    public BaseBlock getFullBlock(BlockVector3 position) {
        return getFullBlock(position.x(), position.y(), position.z());
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        return Forge1710Biomes.toFawe(getWorld().getBiomeGenForCoords(x, z));
    }

    @Override
    public BiomeType getBiome(BlockVector3 position) {
        return getBiomeType(position.x(), position.y(), position.z());
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome) {
        return setBiome(position.x(), position.y(), position.z(), biome);
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome) {
        int nativeId = Forge1710Biomes.toNative(biome);
        if (nativeId < 0) {
            return false;
        }
        return onMainThread(() -> {
            Chunk chunk = getWorld().getChunkFromChunkCoords(x >> 4, z >> 4);
            int[] ids = Forge1710Biomes.read(chunk);
            ids[(z & 15) << 4 | (x & 15)] = nativeId;
            Forge1710Biomes.write(chunk, ids);
            return true;
        });
    }

    @Override
    public List<? extends Entity> getEntities(Region region) {
        return onMainThread(() -> {
            List<Entity> out = new ArrayList<>();
            for (Object o : getWorld().loadedEntityList) {
                net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) o;
                if (entity instanceof EntityPlayer || entity.isDead) {
                    continue;
                }
                if (region.contains(BlockVector3.at(Math.floor(entity.posX), Math.floor(entity.posY), Math.floor(entity.posZ)))) {
                    out.add(new Forge1710Entity(entity));
                }
            }
            return out;
        });
    }

    @Override
    public List<? extends Entity> getEntities() {
        return onMainThread(() -> {
            List<Entity> out = new ArrayList<>();
            for (Object o : getWorld().loadedEntityList) {
                net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) o;
                if (!(entity instanceof EntityPlayer) && !entity.isDead) {
                    out.add(new Forge1710Entity(entity));
                }
            }
            return out;
        });
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block, SideEffectSet sideEffects)
            throws WorldEditException {
        int x = position.x();
        int y = position.y();
        int z = position.z();
        if (y < 0 || y > 255) {
            return false;
        }
        int nativeId = NativeBlockMapper.get().toNative(block.getOrdinal());
        if (nativeId < 0) {
            return false;
        }
        int flags = 2 | (sideEffects.shouldApply(SideEffect.NEIGHBORS) ? 1 : 0);
        return onMainThread(() -> getWorld().setBlock(x, y, z, Block.getBlockById(nativeId >> 4), nativeId & 15, flags));
    }

    @Override
    public Set<SideEffect> applySideEffects(BlockVector3 position, BlockState previousType, SideEffectSet sideEffectSet) {
        return Collections.emptySet();
    }

    @Override
    public boolean clearContainerBlockContents(BlockVector3 position) {
        return onMainThread(() -> {
            TileEntity tile = getWorld().getTileEntity(position.x(), position.y(), position.z());
            if (!(tile instanceof IInventory inventory)) {
                return false;
            }
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                inventory.setInventorySlotContents(i, null);
            }
            return true;
        });
    }

    @Override
    public void dropItem(Vector3 position, BaseItemStack item) {
        net.minecraft.item.ItemStack stack = Forge1710Adapter.toNative(item);
        if (stack == null) {
            return;
        }
        onMainThread(() -> {
            WorldServer world = getWorld();
            EntityItem entity = new EntityItem(world, position.x(), position.y(), position.z(), stack);
            entity.delayBeforeCanPickup = 10;
            return world.spawnEntityInWorld(entity);
        });
    }

    @Override
    public void simulateBlockMine(BlockVector3 position) {
        onMainThread(() -> {
            WorldServer world = getWorld();
            int x = position.x();
            int y = position.y();
            int z = position.z();
            Block block = world.getBlock(x, y, z);
            int meta = world.getBlockMetadata(x, y, z);
            block.dropBlockAsItem(world, x, y, z, meta, 0);
            return world.setBlockToAir(x, y, z);
        });
    }

    @Override
    public boolean generateTree(TreeType type, EditSession editSession, BlockVector3 position)
            throws com.sk89q.worldedit.MaxChangedBlocksException {
        return Forge1710Trees.generate(this, type.id(), editSession, position);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean generateTree(com.sk89q.worldedit.util.TreeGenerator.TreeType type, EditSession editSession,
            BlockVector3 position) throws com.sk89q.worldedit.MaxChangedBlocksException {
        return Forge1710Trees.generate(this, Forge1710Trees.idFor(type), editSession, position);
    }

    /**
     * Terrain-only regeneration: the world's chunk generator builds a fresh chunk that is never registered with the
     * world, and its blocks (and biomes, if requested) are copied into the extent, so the result is recorded in history.
     * Population (trees, ores, structures) is not repeated, because 1.7.10 populators write into the live world.
     */
    @Override
    public boolean regenerate(Region region, com.sk89q.worldedit.extent.Extent extent,
            com.sk89q.worldedit.world.RegenOptions options) {
        WorldServer world = getWorld();
        NativeBlockMapper mapper = NativeBlockMapper.get();
        try {
            for (com.sk89q.worldedit.math.BlockVector2 chunkPos : region.getChunks()) {
                Chunk fresh = onMainThread(() -> world.theChunkProviderServer.currentChunkProvider
                        .provideChunk(chunkPos.x(), chunkPos.z()));
                int bx = chunkPos.x() << 4;
                int bz = chunkPos.z() << 4;
                int[] biomes = options.shouldRegenBiomes() ? Forge1710Biomes.read(fresh) : null;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = Math.max(0, region.getMinimumY()); y <= Math.min(255, region.getMaximumY()); y++) {
                            BlockVector3 pos = BlockVector3.at(bx + lx, y, bz + lz);
                            if (!region.contains(pos)) {
                                continue;
                            }
                            extent.setBlock(pos, mapper.toState(fresh.getBlock(lx, y, lz), fresh.getBlockMetadata(lx, y, lz)));
                            if (biomes != null) {
                                BiomeType biome = options.hasBiomeType() ? options.getBiomeType()
                                        : Forge1710Biomes.toFawe(biomes[lz << 4 | lx]);
                                if (biome != null) {
                                    extent.setBiome(pos, biome);
                                }
                            }
                        }
                    }
                }
            }
            return true;
        } catch (WorldEditException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public BlockVector3 getSpawnPosition() {
        ChunkCoordinates spawn = getWorld().getSpawnPoint();
        return BlockVector3.at(spawn.posX, spawn.posY, spawn.posZ);
    }

    @Override
    public void refreshChunk(int chunkX, int chunkZ) {
        TaskManager.taskManager().task(() -> resendChunk(getWorld(), chunkX, chunkZ));
    }

    /**
     * Must be called on the server thread.
     */
    public static void resendChunk(WorldServer world, int chunkX, int chunkZ) {
        if (!world.theChunkProviderServer.chunkExists(chunkX, chunkZ)) {
            return;
        }
        Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
        S21PacketChunkData packet = null;
        for (Object o : world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (world.getPlayerManager().isPlayerWatchingChunk(player, chunkX, chunkZ)) {
                if (packet == null) {
                    // Non-full update of every allocated section; a full update would also resend biomes.
                    packet = new S21PacketChunkData(chunk, false, 0xFFFF);
                }
                player.playerNetServerHandler.sendPacket(packet);
            }
        }
    }

    @Override
    public IChunkGet get(int chunkX, int chunkZ) {
        return new Forge1710GetBlocks(this, chunkX, chunkZ);
    }

    @Override
    public void sendFakeChunk(@Nullable Player player, ChunkPacket packet) {
    }

    @Override
    public void flush() {
    }

    @Override
    public boolean tile(int x, int y, int z, FaweCompoundTag tile) {
        return false;
    }

    @Override
    public String toString() {
        return "Forge1710World{" + name + "}";
    }

}
