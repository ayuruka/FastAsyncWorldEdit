package com.fastasyncworldedit.forge1710.entity;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.forge1710.registry.NativeBlockMapper;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.extent.inventory.BlockBagException;
import com.sk89q.worldedit.extent.inventory.OutOfBlocksException;
import com.sk89q.worldedit.extent.inventory.OutOfSpaceException;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Survival inventory as a block source ({@code use-inventory=true}): placing a block takes one matching item from the
 * player's main inventory, breaking one gives it back.
 *
 * <p>A block matches the item a player would place it with ({@link Item#getItemFromBlock}, with the damage value the
 * block drops for its metadata, e.g. wool colours and log species; stairs of any facing use the plain stair item). Blocks
 * without an item block (redstone wire, doors, ...) use the item they drop. The inventory is copied on first use and
 * written back on the server thread when the edit flushes.</p>
 */
public class Forge1710PlayerBlockBag extends BlockBag {

    private static final Random RANDOM = new Random();

    private final EntityPlayerMP player;
    private ItemStack[] items;

    public Forge1710PlayerBlockBag(EntityPlayerMP player) {
        this.player = player;
    }

    private record Wanted(Item item, int damage) {

        boolean matches(ItemStack stack) {
            return stack != null && stack.getItem() == item && (!item.getHasSubtypes() || stack.getItemDamage() == damage);
        }

    }

    private void loadInventory() {
        if (items == null) {
            items = onMainThread(() -> {
                ItemStack[] source = player.inventory.mainInventory;
                ItemStack[] copy = new ItemStack[source.length];
                for (int i = 0; i < source.length; i++) {
                    copy[i] = source[i] == null ? null : source[i].copy();
                }
                return copy;
            });
        }
    }

    private static Wanted wanted(BlockState state) {
        int nativeId = NativeBlockMapper.get().toNative(state.getOrdinal());
        if (nativeId < 0) {
            return null;
        }
        Block block = Block.getBlockById(nativeId >> 4);
        int meta = nativeId & 15;
        Item item = Item.getItemFromBlock(block);
        if (item == null) {
            item = block.getItemDropped(meta, RANDOM, 0);
        }
        return item == null ? null : new Wanted(item, block.damageDropped(meta));
    }

    @Override
    public void fetchBlock(BlockState blockState) throws BlockBagException {
        if (blockState.getBlockType().getMaterial().isAir()) {
            throw new IllegalArgumentException("Can't fetch air block");
        }
        Wanted wanted = wanted(blockState);
        if (wanted == null) {
            throw new OutOfBlocksException();
        }
        loadInventory();
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack stack = items[slot];
            if (!wanted.matches(stack)) {
                continue;
            }
            if (stack.stackSize < 0) {
                return; // unlimited
            }
            if (stack.stackSize > 1) {
                stack.stackSize--;
            } else {
                items[slot] = null;
            }
            return;
        }
        throw new OutOfBlocksException();
    }

    @Override
    public void storeBlock(BlockState blockState, int amount) throws BlockBagException {
        if (blockState.getBlockType().getMaterial().isAir()) {
            throw new IllegalArgumentException("Can't store air block");
        }
        Wanted wanted = wanted(blockState);
        if (wanted == null) {
            throw new IllegalArgumentException("This block cannot be stored");
        }
        loadInventory();
        int freeSlot = -1;
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack stack = items[slot];
            if (stack == null) {
                if (freeSlot == -1) {
                    freeSlot = slot;
                }
                continue;
            }
            if (!wanted.matches(stack)) {
                continue;
            }
            if (stack.stackSize < 0) {
                return; // unlimited
            }
            int space = stack.getMaxStackSize() - stack.stackSize;
            if (space <= 0) {
                continue;
            }
            if (space >= amount) {
                stack.stackSize += amount;
                return;
            }
            stack.stackSize += space;
            amount -= space;
        }
        if (freeSlot > -1) {
            items[freeSlot] = new ItemStack(wanted.item(), amount, wanted.damage());
            return;
        }
        throw new OutOfSpaceException(blockState.getBlockType());
    }

    @Override
    public void flushChanges() {
        ItemStack[] changed = items;
        if (changed == null) {
            return;
        }
        items = null;
        onMainThread(() -> {
            ItemStack[] target = player.inventory.mainInventory;
            System.arraycopy(changed, 0, target, 0, Math.min(changed.length, target.length));
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            return null;
        });
    }

    @Override
    public void addSourcePosition(Location pos) {
    }

    @Override
    public void addSingleSourcePosition(Location pos) {
    }

    private static <T> T onMainThread(Supplier<T> supplier) {
        if (Fawe.isMainThread()) {
            return supplier.get();
        }
        return TaskManager.taskManager().sync(supplier);
    }

}
