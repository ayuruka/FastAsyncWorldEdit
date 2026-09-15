package com.fastasyncworldedit.forge1710.entity;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.forge1710.Forge1710Adapter;
import com.fastasyncworldedit.forge1710.internal.NativeData;
import com.fastasyncworldedit.forge1710.registry.Forge1710EntityTypes;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.entity.EntityType;
import net.minecraft.entity.EntityList;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;

/**
 * WorldEdit view of a live 1.7.10 entity. State reads and changes run on the server thread.
 */
public class Forge1710Entity implements Entity {

    private final WeakReference<net.minecraft.entity.Entity> entityRef;

    public Forge1710Entity(net.minecraft.entity.Entity entity) {
        this.entityRef = new WeakReference<>(entity);
    }

    @Nullable
    public net.minecraft.entity.Entity getEntity() {
        return entityRef.get();
    }

    @Nullable
    @Override
    public BaseEntity getState() {
        net.minecraft.entity.Entity entity = entityRef.get();
        if (entity == null) {
            return null;
        }
        return TaskManager.taskManager().sync(() -> {
            EntityType type = Forge1710EntityTypes.type(EntityList.getEntityString(entity));
            FaweCompoundTag tag = type == null ? null : NativeData.entity(entity);
            return tag == null ? null : new BaseEntity(type, LazyReference.computed(tag.linTag()));
        });
    }

    @Override
    public Location getLocation() {
        net.minecraft.entity.Entity entity = entityRef.get();
        if (entity == null) {
            throw new IllegalStateException("Entity has been removed");
        }
        return new Location(Forge1710Adapter.adapt((WorldServer) entity.worldObj), entity.posX, entity.posY, entity.posZ,
                entity.rotationYaw, entity.rotationPitch);
    }

    @Override
    public boolean setLocation(Location location) {
        net.minecraft.entity.Entity entity = entityRef.get();
        if (entity == null) {
            return false;
        }
        TaskManager.taskManager().sync(() -> {
            entity.setLocationAndAngles(location.x(), location.y(), location.z(), location.getYaw(), location.getPitch());
            return null;
        });
        return true;
    }

    @Override
    public Extent getExtent() {
        net.minecraft.entity.Entity entity = entityRef.get();
        if (entity == null) {
            throw new IllegalStateException("Entity has been removed");
        }
        return Forge1710Adapter.adapt((WorldServer) entity.worldObj);
    }

    @Override
    public boolean remove() {
        net.minecraft.entity.Entity entity = entityRef.get();
        if (entity == null) {
            return false;
        }
        TaskManager.taskManager().sync(() -> {
            entity.setDead();
            return null;
        });
        return true;
    }

    @Nullable
    @Override
    public <T> T getFacet(Class<? extends T> cls) {
        return null;
    }

}
