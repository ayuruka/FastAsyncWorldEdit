package com.fastasyncworldedit.forge1710.internal;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.forge1710.registry.Forge1710EntityTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * TileEntity and entity NBT in the shape FAWE expects. Server thread only: 1.7.10 tile entities and entities are not
 * safe to serialise concurrently.
 */
public final class NativeData {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");

    private NativeData() {
    }

    @Nullable
    public static FaweCompoundTag tile(TileEntity tile) {
        try {
            NBTTagCompound tag = new NBTTagCompound();
            tile.writeToNBT(tag);
            return NBTConverter.toFawe(tag);
        } catch (Throwable t) {
            LOGGER.warn("Could not save tile entity {} at {},{},{}", tile.getClass().getName(), tile.xCoord, tile.yCoord,
                    tile.zCoord, t);
            return null;
        }
    }

    /**
     * Loads NBT into the tile entity at the position (creating it through the block if needed) and syncs it to clients.
     */
    public static void loadTile(World world, int x, int y, int z, FaweCompoundTag data) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) {
            return;
        }
        try {
            NBTTagCompound tag = NBTConverter.toNative(data);
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            tile.readFromNBT(tag);
            tile.markDirty();
            world.markBlockForUpdate(x, y, z);
        } catch (Throwable t) {
            LOGGER.warn("Could not load tile entity data at {},{},{}", x, y, z, t);
        }
    }

    /**
     * Entity NBT with FAWE's "Id" (namespaced) next to 1.7.10's "id", or null for entities that are not saved (players,
     * riders, dead entities).
     */
    @Nullable
    public static FaweCompoundTag entity(Entity entity) {
        if (entity instanceof EntityPlayer) {
            return null;
        }
        try {
            NBTTagCompound tag = new NBTTagCompound();
            if (!entity.writeToNBTOptional(tag)) {
                return null;
            }
            String id = Forge1710EntityTypes.toFawe(EntityList.getEntityString(entity));
            if (id != null) {
                tag.setString("Id", id);
            }
            return NBTConverter.toFawe(tag);
        } catch (Throwable t) {
            LOGGER.warn("Could not save entity {}", entity, t);
            return null;
        }
    }

    /**
     * Creates and spawns an entity from FAWE NBT ("Id" namespaced or "id" native, "Pos", optional "Rotation").
     */
    @Nullable
    public static Entity spawn(World world, FaweCompoundTag data) {
        try {
            NBTTagCompound tag = NBTConverter.toNative(data);
            String nativeName = Forge1710EntityTypes.toNative(tag.hasKey("Id") ? tag.getString("Id") : tag.getString("id"));
            if (nativeName == null) {
                LOGGER.warn("Unknown entity type in {}", tag);
                return null;
            }
            tag.removeTag("Id");
            tag.setString("id", nativeName);
            Entity entity = EntityList.createEntityFromNBT(tag, world);
            if (entity == null) {
                return null;
            }
            // readFromNBT already placed the entity from "Pos"/"Rotation"; setLocationAndAngles would add yOffset again
            // (an EnderCrystal rose one block on every copy).
            return world.spawnEntityInWorld(entity) ? entity : null;
        } catch (Throwable t) {
            LOGGER.warn("Could not create entity from {}", data, t);
            return null;
        }
    }

}
