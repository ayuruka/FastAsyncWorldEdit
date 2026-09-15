package com.fastasyncworldedit.forge1710.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.BlockSlab;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rotates and flips 1.7.10 blocks by their metadata (1.7.10 blocks have no orientation properties).
 *
 * <p>Vanilla orientation data comes from WorldEdit 6's legacy block registry (bit mask and direction vector per metadata
 * value). Modded blocks use the table of the vanilla block they extend (stairs, logs and pillars, slabs, doors,
 * trapdoors, fence gates, buttons, levers, torches, ladders, chests, furnaces, vines...), so decorative mod blocks rotate
 * without a per-mod mapping. A state is rotated by turning each group's direction vector with the transform and picking
 * the value whose direction is closest.</p>
 */
public final class LegacyOrientation implements BlockTransformExtent.PlatformStateTransformer {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");

    /** Groups of one metadata "property" (e.g. facing, half). */
    private static final class Group {

        int mask = 15;
        int requireMask;
        int requireData;
        @Nullable
        int[] onlyData;
        boolean flags;
        final List<int[]> data = new ArrayList<>();
        final List<Vector3> directions = new ArrayList<>();

        void add(int value, int x, int y, int z) {
            data.add(new int[]{value});
            directions.add(Vector3.at(x, y, z).normalize());
        }

    }

    private final Map<Integer, List<Group>> vanilla = new HashMap<>();
    private final Map<Class<?>, Integer> byClass = new HashMap<>();
    private final Map<String, List<Group>> byType = new ConcurrentHashMap<>();
    private final Map<String, Boolean> handled = new ConcurrentHashMap<>();

    private LegacyOrientation() {
    }

    public static LegacyOrientation install() {
        LegacyOrientation orientation = new LegacyOrientation();
        orientation.loadVanilla();
        orientation.addExtras();
        orientation.indexClasses();
        BlockTransformExtent.setPlatformTransformer(orientation);
        LOGGER.info("Orientation data for {} vanilla blocks, {} block classes", orientation.vanilla.size(),
                orientation.byClass.size());
        return orientation;
    }

    private void loadVanilla() {
        try (InputStream in = LegacyOrientation.class.getClassLoader().getResourceAsStream("com/fastasyncworldedit/forge1710/legacy-orientation.json")) {
            if (in == null) {
                LOGGER.warn("legacy-orientation.json is missing; rotation of 1.7.10 blocks is disabled");
                return;
            }
            JsonArray blocks = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : blocks) {
                JsonObject block = element.getAsJsonObject();
                List<Group> groups = new ArrayList<>();
                for (Map.Entry<String, JsonElement> state : block.getAsJsonObject("states").entrySet()) {
                    JsonObject json = state.getValue().getAsJsonObject();
                    Group group = new Group();
                    group.mask = json.get("dataMask").getAsInt();
                    for (Map.Entry<String, JsonElement> value : json.getAsJsonObject("values").entrySet()) {
                        JsonObject v = value.getValue().getAsJsonObject();
                        JsonArray dir = v.getAsJsonArray("direction");
                        group.add(v.get("data").getAsInt(), dir.get(0).getAsInt(), dir.get(1).getAsInt(), dir.get(2).getAsInt());
                    }
                    groups.add(group);
                }
                vanilla.put(block.get("legacyId").getAsInt(), groups);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load legacy-orientation.json", e);
        }
    }

    /** Blocks missing from WorldEdit 6's table. */
    private void addExtras() {
        // Doors: the lower half stores the facing (0 east, 1 south, 2 west, 3 north); the upper half stores the hinge.
        Group door = new Group();
        door.mask = 3;
        door.requireMask = 8;
        door.requireData = 0;
        door.add(0, 1, 0, 0);
        door.add(1, 0, 0, 1);
        door.add(2, -1, 0, 0);
        door.add(3, 0, 0, -1);
        vanilla.put(64, List.of(door));
        vanilla.put(71, List.of(door));
        // Hay bale: same axis bits as logs.
        List<Group> log = vanilla.get(17);
        if (log != null) {
            vanilla.putIfAbsent(170, log);
        }
        // Pillar quartz: 2 vertical, 3 east-west, 4 north-south; 0 and 1 have no axis.
        Group quartz = new Group();
        quartz.mask = 7;
        quartz.onlyData = new int[]{2, 3, 4};
        quartz.add(2, 0, 1, 0);
        quartz.add(2, 0, -1, 0);
        quartz.add(3, 1, 0, 0);
        quartz.add(3, -1, 0, 0);
        quartz.add(4, 0, 0, 1);
        quartz.add(4, 0, 0, -1);
        vanilla.put(155, List.of(quartz));
        // Vines: one bit per attached side (south 1, west 2, north 4, east 8).
        Group vine = new Group();
        vine.flags = true;
        vine.add(1, 0, 0, 1);
        vine.add(2, -1, 0, 0);
        vine.add(4, 0, 0, -1);
        vine.add(8, 1, 0, 0);
        vanilla.put(106, List.of(vine));
    }

    private void indexClasses() {
        for (Map.Entry<Integer, List<Group>> entry : vanilla.entrySet()) {
            Block block = Block.getBlockById(entry.getKey());
            if (block != null && block != net.minecraft.init.Blocks.air) {
                byClass.putIfAbsent(block.getClass(), entry.getKey());
            }
        }
        // Common base classes whose vanilla subclasses share one layout.
        byClass.putIfAbsent(BlockLog.class, 17);
        byClass.putIfAbsent(BlockRotatedPillar.class, 170);
        byClass.putIfAbsent(BlockSlab.class, 44);
        byClass.putIfAbsent(BlockButton.class, 77);
    }

    @Nullable
    private List<Group> groupsFor(Block block) {
        String name = GameData.getBlockRegistry().getNameForObject(block);
        if (name != null && name.startsWith("minecraft:")) {
            return vanilla.get(Block.getIdFromBlock(block));
        }
        for (Class<?> c = block.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            Integer id = byClass.get(c);
            if (id != null) {
                return vanilla.get(id);
            }
        }
        return null;
    }

    @Override
    public boolean handles(BlockType type) {
        return handled.computeIfAbsent(type.id(), id -> {
            if (!type.getPropertyMap().containsKey(NativeBlockMapper.META_PROPERTY_NAME)) {
                return false;
            }
            Block block = NativeBlockMapper.get().getBlock(type);
            List<Group> groups = block == null ? null : groupsFor(block);
            if (groups == null) {
                return false;
            }
            byType.put(id, groups);
            return true;
        });
    }

    @Override
    public int transform(BlockState state, Transform transform) {
        List<Group> groups = byType.get(state.getBlockType().id());
        if (groups == null) {
            return -1;
        }
        int nativeId = NativeBlockMapper.get().toNative(state.getOrdinal());
        if (nativeId < 0) {
            return -1;
        }
        int meta = nativeId & 15;
        int newMeta = meta;
        Vector3 origin = transform.apply(Vector3.ZERO);
        for (Group group : groups) {
            newMeta = apply(group, newMeta, transform, origin);
        }
        if (newMeta == meta) {
            return -1;
        }
        char ordinal = NativeBlockMapper.get().toOrdinal(nativeId >> 4, newMeta);
        return BlockTypesCache.states[ordinal].getInternalId();
    }

    private static int apply(Group group, int meta, Transform transform, Vector3 origin) {
        if ((meta & group.requireMask) != group.requireData) {
            return meta;
        }
        if (group.flags) {
            int result = meta & ~15;
            for (int i = 0; i < group.data.size(); i++) {
                int bit = group.data.get(i)[0];
                if ((meta & bit) != 0) {
                    result |= closest(group, rotate(group.directions.get(i), transform, origin));
                }
            }
            return result;
        }
        int current = meta & group.mask;
        if (group.onlyData != null) {
            boolean allowed = false;
            for (int value : group.onlyData) {
                allowed |= value == current;
            }
            if (!allowed) {
                return meta;
            }
        }
        for (int i = 0; i < group.data.size(); i++) {
            if (group.data.get(i)[0] == current) {
                int result = closest(group, rotate(group.directions.get(i), transform, origin));
                return (meta & ~group.mask) | (result & group.mask);
            }
        }
        return meta;
    }

    private static Vector3 rotate(Vector3 direction, Transform transform, Vector3 origin) {
        return transform.apply(direction).subtract(origin);
    }

    private static int closest(Group group, Vector3 direction) {
        double best = -2;
        int result = group.data.get(0)[0];
        Vector3 normal = direction.lengthSq() == 0 ? direction : direction.normalize();
        for (int i = 0; i < group.data.size(); i++) {
            double dot = group.directions.get(i).dot(normal);
            if (dot > best) {
                best = dot;
                result = group.data.get(i)[0];
            }
        }
        return result;
    }

}
