package com.fastasyncworldedit.forge1710.world;

import com.fastasyncworldedit.forge1710.registry.NativeBlockMapper;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.TreeGenerator;
import com.sk89q.worldedit.world.generation.TreeType;
import net.minecraft.block.Block;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.feature.WorldGenBigMushroom;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import net.minecraft.world.gen.feature.WorldGenCanopyTree;
import net.minecraft.world.gen.feature.WorldGenForest;
import net.minecraft.world.gen.feature.WorldGenMegaJungle;
import net.minecraft.world.gen.feature.WorldGenMegaPineTree;
import net.minecraft.world.gen.feature.WorldGenSavannaTree;
import net.minecraft.world.gen.feature.WorldGenShrub;
import net.minecraft.world.gen.feature.WorldGenSwamp;
import net.minecraft.world.gen.feature.WorldGenTaiga1;
import net.minecraft.world.gen.feature.WorldGenTaiga2;
import net.minecraft.world.gen.feature.WorldGenTrees;
import net.minecraft.world.gen.feature.WorldGenerator;
import net.minecraftforge.common.util.BlockSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Tree generation with the 1.7.10 world generators, recorded in the edit session so trees can be undone.
 *
 * <p>The generator runs on the server thread with Forge's block snapshot capture enabled. The captured changes are
 * rolled back and replayed through the {@link EditSession}, which records them in history like any other edit.</p>
 */
final class Forge1710Trees {

    /** Tree ids offered to the registry-based commands (//forestgen, tree brushes). */
    private static final Map<String, Function<Random, WorldGenerator>> BY_ID = new LinkedHashMap<>();

    static {
        BY_ID.put("minecraft:oak", r -> new WorldGenTrees(false));
        BY_ID.put("minecraft:fancy_oak", r -> new WorldGenBigTree(false));
        BY_ID.put("minecraft:spruce", r -> new WorldGenTaiga2(false));
        BY_ID.put("minecraft:pine", r -> new WorldGenTaiga1());
        BY_ID.put("minecraft:mega_spruce", r -> new WorldGenMegaPineTree(false, false));
        BY_ID.put("minecraft:mega_pine", r -> new WorldGenMegaPineTree(false, true));
        BY_ID.put("minecraft:birch", r -> new WorldGenForest(false, false));
        BY_ID.put("minecraft:super_birch", r -> new WorldGenForest(false, true));
        BY_ID.put("minecraft:jungle_tree", r -> new WorldGenTrees(false, 4 + r.nextInt(7), 3, 3, true));
        BY_ID.put("minecraft:jungle_tree_no_vine", r -> new WorldGenTrees(false, 4 + r.nextInt(7), 3, 3, false));
        BY_ID.put("minecraft:mega_jungle_tree", r -> new WorldGenMegaJungle(false, 10, 20, 3, 3));
        BY_ID.put("minecraft:jungle_bush", r -> new WorldGenShrub(3, 0));
        BY_ID.put("minecraft:swamp_oak", r -> new WorldGenSwamp());
        BY_ID.put("minecraft:acacia", r -> new WorldGenSavannaTree(false));
        BY_ID.put("minecraft:dark_oak", r -> new WorldGenCanopyTree(false));
        BY_ID.put("minecraft:huge_brown_mushroom", r -> new WorldGenBigMushroom(0));
        BY_ID.put("minecraft:huge_red_mushroom", r -> new WorldGenBigMushroom(1));
    }

    private Forge1710Trees() {
    }

    static void registerTypes() {
        for (String id : BY_ID.keySet()) {
            if (TreeType.REGISTRY.get(id) == null) {
                TreeType.REGISTRY.register(id, new TreeType(id));
            }
        }
    }

    @Nullable
    static String idFor(TreeGenerator.TreeType type) {
        return switch (type) {
            case TREE -> "minecraft:oak";
            case BIG_TREE -> "minecraft:fancy_oak";
            case REDWOOD -> "minecraft:spruce";
            case TALL_REDWOOD, PINE -> "minecraft:pine";
            case MEGA_REDWOOD -> "minecraft:mega_spruce";
            case BIRCH -> "minecraft:birch";
            case TALL_BIRCH -> "minecraft:super_birch";
            case JUNGLE -> "minecraft:mega_jungle_tree";
            case SMALL_JUNGLE -> "minecraft:jungle_tree";
            case SHORT_JUNGLE -> "minecraft:jungle_tree_no_vine";
            case JUNGLE_BUSH -> "minecraft:jungle_bush";
            case SWAMP -> "minecraft:swamp_oak";
            case ACACIA -> "minecraft:acacia";
            case DARK_OAK -> "minecraft:dark_oak";
            case BROWN_MUSHROOM -> "minecraft:huge_brown_mushroom";
            case RED_MUSHROOM -> "minecraft:huge_red_mushroom";
            default -> null;
        };
    }

    private record Placement(int x, int y, int z, Block block, int meta) {

    }

    static boolean generate(Forge1710World world, @Nullable String id, EditSession editSession, BlockVector3 position)
            throws MaxChangedBlocksException {
        Function<Random, WorldGenerator> factory = id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT));
        if (factory == null) {
            return false;
        }
        List<Placement> placements = Forge1710World.onMainThread(() -> {
            WorldServer nms = world.getWorld();
            Random random = new Random();
            WorldGenerator generator = factory.apply(random);
            boolean capturing = nms.captureBlockSnapshots;
            List<BlockSnapshot> snapshots;
            boolean success;
            nms.captureBlockSnapshots = true;
            int before = nms.capturedBlockSnapshots.size();
            // Region forests pass the ground block; 1.7.10 generators expect the sapling position above it.
            int y = position.y();
            if (!nms.getBlock(position.x(), y, position.z()).getMaterial().isReplaceable()) {
                y++;
            }
            try {
                success = generator.generate(nms, random, position.x(), y, position.z());
            } finally {
                nms.captureBlockSnapshots = capturing;
                snapshots = new ArrayList<>(nms.capturedBlockSnapshots.subList(before, nms.capturedBlockSnapshots.size()));
                nms.capturedBlockSnapshots.subList(before, nms.capturedBlockSnapshots.size()).clear();
            }
            // What the generator placed (last write wins), then undo it in reverse order.
            Map<BlockVector3, Placement> placed = new LinkedHashMap<>();
            for (BlockSnapshot snapshot : snapshots) {
                BlockVector3 key = BlockVector3.at(snapshot.x, snapshot.y, snapshot.z);
                placed.put(key, new Placement(snapshot.x, snapshot.y, snapshot.z, nms.getBlock(snapshot.x, snapshot.y,
                        snapshot.z), nms.getBlockMetadata(snapshot.x, snapshot.y, snapshot.z)));
            }
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                snapshots.get(i).restore(true, false);
            }
            return success ? new ArrayList<>(placed.values()) : null;
        });
        if (placements == null) {
            return false;
        }
        NativeBlockMapper mapper = NativeBlockMapper.get();
        for (Placement placement : placements) {
            editSession.setBlock(BlockVector3.at(placement.x(), placement.y(), placement.z()),
                    mapper.toState(placement.block(), placement.meta()));
        }
        return true;
    }

}
