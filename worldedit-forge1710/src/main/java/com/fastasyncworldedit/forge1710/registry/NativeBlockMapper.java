package com.fastasyncworldedit.forge1710.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps Minecraft 1.7.10 blocks (numeric id + 4-bit metadata) to FAWE block states and back.
 *
 * <p>Vanilla blocks are exposed with their current Minecraft ids and block state properties
 * ({@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight]}), using WorldEdit's legacy id table and the
 * Minecraft 1.13.2 property definitions ({@code modern-blocks.json}). FAWE's own logic (rotation, snow, flora, masks with
 * properties, schematics from newer versions) therefore works on vanilla blocks. Modded blocks declare no states in
 * 1.7.10, so they are exposed as {@code namespace:name[legacy_meta=0..15]}.</p>
 *
 * <p>A modern state that 1.7.10 cannot represent (e.g. inner stair corners) is written as the legacy state with the most
 * matching property values.</p>
 */
public final class NativeBlockMapper {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");

    public static final String META_PROPERTY_NAME = "legacy_meta";
    /**
     * Created lazily: AbstractProperty reads BlockTypesCache.BIT_OFFSET in its constructor, so properties may only be built
     * while BlockTypesCache initialises (which asks {@link #getProperties}) or later.
     */
    private static volatile IntegerProperty metaProperty;

    /** FAWE ordinals are chars and Character.MAX_VALUE is used as an "unset" sentinel by schematic writers. */
    private static final int MAX_STATES = Character.MAX_VALUE;
    private static final List<String> PSEUDO_AIR = List.of("minecraft:cave_air", "minecraft:void_air");

    private static volatile NativeBlockMapper instance;

    private enum Kind { MODERN, LEGACY_META, PROPERTYLESS, PSEUDO_AIR }

    private static final class TypeInfo {

        final String id;
        final Kind kind;
        /** Block used for materials and names. */
        final Block block;
        /** Modern types: property definitions and default state string. */
        @Nullable
        JsonObject properties;
        String defaultState;
        /** Modern types: legacy (combined id << 4 | meta) -> property values, in preference order. */
        final List<int[]> legacyCombined = new ArrayList<>();
        final List<Map<String, String>> legacyProperties = new ArrayList<>();

        TypeInfo(String id, Kind kind, Block block) {
            this.id = id;
            this.kind = kind;
            this.block = block;
            this.defaultState = id;
        }

    }

    /** FAWE block type id -> type information, vanilla first, ordered by native id. */
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    /** Combined native id (id << 4 | meta) of vanilla blocks -> [modern type id, property values]. */
    private final Map<Integer, Object[]> vanillaStates = new HashMap<>();
    /** 1.7.10 vanilla names -> FAWE ids, for input with old names. */
    private final Map<String, String> legacyNames = new LinkedHashMap<>();

    private volatile char[] nativeToOrdinal;
    private volatile int[] ordinalToNative;

    private NativeBlockMapper() {
        JsonObject modern = readJson("com/fastasyncworldedit/forge1710/modern-blocks.json");
        JsonObject legacyBlocks = readJson("com/sk89q/worldedit/world/registry/legacy.json").getAsJsonObject("blocks");

        List<Block> sorted = new ArrayList<>();
        for (Object o : GameData.getBlockRegistry()) {
            sorted.add((Block) o);
        }
        sorted.sort((a, b) -> Integer.compare(Block.getIdFromBlock(a), Block.getIdFromBlock(b)));

        // Legacy table grouped by native id.
        Map<Integer, Map<Integer, String>> legacyById = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : legacyBlocks.entrySet()) {
            String[] parts = entry.getKey().split(":");
            legacyById.computeIfAbsent(Integer.parseInt(parts[0]), k -> new TreeMap<>())
                    .put(Integer.parseInt(parts[1]), entry.getValue().getAsString());
        }

        int states = 0;
        List<Block> moddedOrUncovered = new ArrayList<>();
        // Vanilla blocks first, so their modern ids win over modded blocks that happen to use the same lower-cased name.
        for (Block block : sorted) {
            String forgeName = GameData.getBlockRegistry().getNameForObject(block);
            if (forgeName == null) {
                continue;
            }
            int id = Block.getIdFromBlock(block);
            Map<Integer, String> legacy = legacyById.get(id);
            if (!forgeName.startsWith("minecraft:") || legacy == null || modern == null) {
                moddedOrUncovered.add(block);
                continue;
            }
            for (int meta = 0; meta < 16; meta++) {
                String state = legacy.getOrDefault(meta, legacy.get(0));
                if (state == null) {
                    state = legacy.values().iterator().next();
                }
                int bracket = state.indexOf('[');
                String typeId = bracket < 0 ? state : state.substring(0, bracket);
                JsonObject definition = modern.getAsJsonObject(typeId);
                if (definition == null) {
                    continue;
                }
                TypeInfo info = types.get(typeId);
                if (info == null) {
                    info = new TypeInfo(typeId, typeId.equals("minecraft:air") ? Kind.PROPERTYLESS : Kind.MODERN, block);
                    info.properties = definition.getAsJsonObject("properties");
                    info.defaultState = definition.get("default").getAsString();
                    types.put(typeId, info);
                    states += stateCount(info.properties);
                }
                Map<String, String> values = parseProperties(state, info.properties);
                vanillaStates.put(id << 4 | meta, new Object[]{typeId, values});
                // Only metadata values listed in the table are exact legacy states for the reverse mapping.
                if (legacy.containsKey(meta)) {
                    info.legacyCombined.add(new int[]{id << 4 | meta});
                    info.legacyProperties.add(values);
                }
            }
            String oldName = forgeName.toLowerCase(Locale.ROOT);
            Object[] base = vanillaStates.get(id << 4);
            if (base != null) {
                legacyNames.putIfAbsent(oldName, (String) base[0]);
            }
        }

        for (Block block : moddedOrUncovered) {
            String forgeName = GameData.getBlockRegistry().getNameForObject(block);
            String id = forgeName.toLowerCase(Locale.ROOT);
            if (id.indexOf(':') < 0) {
                id = "minecraft:" + id;
            }
            if (types.containsKey(id)) {
                LOGGER.warn("Block '{}' collides with an existing block id '{}'; only the first is editable", forgeName, id);
                continue;
            }
            boolean air = id.equals("minecraft:air");
            types.put(id, new TypeInfo(id, air ? Kind.PROPERTYLESS : Kind.LEGACY_META, block));
            states += air ? 1 : 16;
        }
        // FAWE reserves fixed ids for cave_air and void_air and sizes its tables from this registry, so they must be listed
        // even though 1.7.10 has no such blocks. They behave as plain air.
        for (String pseudoAir : PSEUDO_AIR) {
            if (!types.containsKey(pseudoAir)) {
                types.put(pseudoAir, new TypeInfo(pseudoAir, Kind.PSEUDO_AIR, Blocks.air));
                states += 1;
            }
        }
        // Blocks renamed after Minecraft 1.13.2, which FAWE's code refers to by their current names.
        Map<String, String> renamed = Map.of(
                "minecraft:short_grass", "minecraft:grass",
                "minecraft:oak_sign", "minecraft:sign",
                "minecraft:oak_wall_sign", "minecraft:wall_sign",
                "minecraft:smooth_stone_slab", "minecraft:stone_slab"
        );
        renamed.forEach((current, old) -> {
            if (types.containsKey(old)) {
                legacyNames.putIfAbsent(current, old);
            }
        });
        // Old names only where they do not shadow a real id.
        legacyNames.keySet().removeIf(types::containsKey);
        legacyNames.entrySet().removeIf(e -> e.getKey().equals(e.getValue()));

        if (states >= MAX_STATES) {
            throw new IllegalStateException("Too many block states for FAWE (" + states + " >= " + MAX_STATES
                    + "); reduce the number of installed block mods");
        }
        long modernTypes = types.values().stream().filter(t -> t.kind == Kind.MODERN).count();
        LOGGER.info("Exposing {} block types ({} vanilla with block states) as {} FAWE block states", types.size(),
                modernTypes, states);
    }

    @Nullable
    private static JsonObject readJson(String path) {
        try (InputStream in = NativeBlockMapper.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.warn("Missing resource {}", path);
                return null;
            }
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Could not read {}", path, e);
            return null;
        }
    }

    private static int stateCount(@Nullable JsonObject properties) {
        int count = 1;
        if (properties != null) {
            for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                count *= entry.getValue().getAsJsonObject().getAsJsonArray("values").size();
            }
        }
        return count;
    }

    /** Property values of a state string, restricted to the properties the type exposes. */
    private static Map<String, String> parseProperties(String state, @Nullable JsonObject properties) {
        Map<String, String> values = new HashMap<>();
        int bracket = state.indexOf('[');
        if (bracket < 0 || properties == null) {
            return values;
        }
        for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && properties.has(pair.substring(0, eq))) {
                values.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return values;
    }

    public static NativeBlockMapper get() {
        NativeBlockMapper local = instance;
        if (local == null) {
            synchronized (NativeBlockMapper.class) {
                local = instance;
                if (local == null) {
                    instance = local = new NativeBlockMapper();
                }
            }
        }
        return local;
    }

    public static IntegerProperty metaProperty() {
        IntegerProperty local = metaProperty;
        if (local == null) {
            synchronized (NativeBlockMapper.class) {
                local = metaProperty;
                if (local == null) {
                    List<Integer> values = new ArrayList<>(16);
                    for (int i = 0; i < 16; i++) {
                        values.add(i);
                    }
                    metaProperty = local = new IntegerProperty(META_PROPERTY_NAME, Collections.unmodifiableList(values));
                }
            }
        }
        return local;
    }

    /**
     * Block types in their default state, in the format {@link BlockTypesCache} expects.
     */
    public List<String> values() {
        List<String> values = new ArrayList<>(types.size());
        for (TypeInfo info : types.values()) {
            values.add(switch (info.kind) {
                case MODERN -> info.defaultState;
                case LEGACY_META -> info.id + "[" + META_PROPERTY_NAME + "=0]";
                case PROPERTYLESS, PSEUDO_AIR -> info.id;
            });
        }
        return values;
    }

    /** 1.7.10 vanilla names (e.g. {@code minecraft:wool}) that resolve to current ids ({@code minecraft:white_wool}). */
    public Map<String, String> legacyNameAliases() {
        return Collections.unmodifiableMap(legacyNames);
    }

    public boolean hasMetaProperty(String id) {
        TypeInfo info = types.get(id);
        return info != null && info.kind == Kind.LEGACY_META;
    }

    public Map<String, ? extends Property<?>> getProperties(String id) {
        TypeInfo info = types.get(id);
        if (info == null) {
            return Collections.emptyMap();
        }
        if (info.kind == Kind.LEGACY_META) {
            return Collections.singletonMap(META_PROPERTY_NAME, metaProperty());
        }
        if (info.kind != Kind.MODERN || info.properties == null) {
            return Collections.emptyMap();
        }
        Map<String, Property<?>> properties = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : info.properties.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> values = new ArrayList<>();
            definition.getAsJsonArray("values").forEach(v -> values.add(v.getAsString()));
            properties.put(entry.getKey(), createProperty(definition.get("type").getAsString(), entry.getKey(), values));
        }
        return properties;
    }

    private static Property<?> createProperty(String type, String key, List<String> values) {
        return switch (type) {
            case "int" -> new IntegerProperty(key, values.stream().map(Integer::parseInt).toList());
            case "bool" -> new BooleanProperty(key, values.stream().map(Boolean::parseBoolean).toList());
            case "direction" -> new DirectionalProperty(key, values.stream()
                    .map(v -> Direction.valueOf(v.toUpperCase(Locale.ROOT))).toList());
            default -> new EnumProperty(key, values);
        };
    }

    @Nullable
    public Block getBlock(String id) {
        TypeInfo info = types.get(id);
        return info == null ? null : info.block;
    }

    @Nullable
    public Block getBlock(BlockType type) {
        return getBlock(type.id());
    }

    /**
     * FAWE ordinal for a native block id and metadata. Unknown ids map to air.
     */
    public char toOrdinal(int blockId, int meta) {
        char[] table = nativeTables();
        int index = (blockId << 4) | (meta & 15);
        if (index < 0 || index >= table.length) {
            return (char) BlockTypesCache.ReservedIDs.AIR;
        }
        return table[index];
    }

    /**
     * Native (blockId << 4 | meta) for a FAWE ordinal, or -1 if the state has no native block.
     */
    public int toNative(int ordinal) {
        int[] table = ordinalToNativeTable();
        return ordinal >= 0 && ordinal < table.length ? table[ordinal] : -1;
    }

    public BlockState toState(Block block, int meta) {
        return BlockTypesCache.states[toOrdinal(Block.getIdFromBlock(block), meta)];
    }

    private char[] nativeTables() {
        char[] local = nativeToOrdinal;
        if (local == null) {
            buildTables();
            local = nativeToOrdinal;
        }
        return local;
    }

    private int[] ordinalToNativeTable() {
        int[] local = ordinalToNative;
        if (local == null) {
            buildTables();
            local = ordinalToNative;
        }
        return local;
    }

    /** Key of a state's values for the properties in {@code names}. */
    private static String key(Map<String, String> values) {
        return new TreeMap<>(values).toString();
    }

    /**
     * Built lazily because FAWE ordinals only exist once {@link BlockTypesCache} has been initialised from this registry.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void buildTables() {
        if (nativeToOrdinal != null) {
            return;
        }
        int maxId = 0;
        for (TypeInfo info : types.values()) {
            maxId = Math.max(maxId, Block.getIdFromBlock(info.block));
        }
        for (Integer combined : vanillaStates.keySet()) {
            maxId = Math.max(maxId, combined >> 4);
        }
        char air = (char) BlockTypesCache.ReservedIDs.AIR;
        char[] toOrdinal = new char[(maxId + 1) << 4];
        Arrays.fill(toOrdinal, air);
        int[] toNative = new int[BlockTypesCache.states.length];
        Arrays.fill(toNative, -1);

        for (TypeInfo info : types.values()) {
            BlockType type = BlockTypes.get(info.id);
            if (type == null) {
                LOGGER.warn("Block {} was not registered with FAWE", info.id);
                continue;
            }
            int blockId = Block.getIdFromBlock(info.block);
            switch (info.kind) {
                case PSEUDO_AIR -> toNative[type.getDefaultState().getOrdinalChar()] = 0;
                case PROPERTYLESS -> {
                    char ordinal = type.getDefaultState().getOrdinalChar();
                    for (int meta = 0; meta < 16; meta++) {
                        toOrdinal[(blockId << 4) | meta] = ordinal;
                    }
                    toNative[ordinal] = blockId << 4;
                }
                case LEGACY_META -> {
                    // BlockTypesCache re-creates properties with per-type bit offsets, so use the type's own instance.
                    Property<Integer> meta = type.getProperty(META_PROPERTY_NAME);
                    for (int m = 0; m < 16; m++) {
                        char ordinal = type.getDefaultState().with(meta, m).getOrdinalChar();
                        toOrdinal[(blockId << 4) | m] = ordinal;
                        toNative[ordinal] = (blockId << 4) | m;
                    }
                }
                case MODERN -> buildModern(info, type, toNative);
            }
        }
        // Native -> modern state for every vanilla id/metadata.
        for (Map.Entry<Integer, Object[]> entry : vanillaStates.entrySet()) {
            BlockType type = BlockTypes.get((String) entry.getValue()[0]);
            if (type == null) {
                continue;
            }
            BlockState state = stateWith(type, (Map<String, String>) entry.getValue()[1]);
            toOrdinal[entry.getKey()] = state.getOrdinalChar();
        }
        ordinalToNative = toNative;
        nativeToOrdinal = toOrdinal;
    }

    /** Modern state -> the legacy state with the most matching property values (exact matches score highest). */
    private void buildModern(TypeInfo info, BlockType type, int[] toNative) {
        if (info.legacyCombined.isEmpty()) {
            return;
        }
        // Prefer higher native ids for exact duplicates (e.g. still water 9 over flowing water 8), then lower metadata.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < info.legacyCombined.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            int idA = info.legacyCombined.get(a)[0] >> 4;
            int idB = info.legacyCombined.get(b)[0] >> 4;
            if (idA != idB) {
                return Integer.compare(idB, idA);
            }
            return Integer.compare(info.legacyCombined.get(a)[0] & 15, info.legacyCombined.get(b)[0] & 15);
        });
        for (BlockState state : type.getAllStates()) {
            int best = -1;
            int bestScore = -1;
            for (int index : order) {
                Map<String, String> legacy = info.legacyProperties.get(index);
                int score = 0;
                for (Map.Entry<String, String> value : legacy.entrySet()) {
                    Property<?> property = type.getProperty(value.getKey());
                    if (property != null && String.valueOf(state.getState(property)).equalsIgnoreCase(value.getValue())) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = info.legacyCombined.get(index)[0];
                }
            }
            toNative[state.getOrdinalChar()] = best;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState stateWith(BlockType type, Map<String, String> values) {
        BlockState state = type.getDefaultState();
        for (Map.Entry<String, String> value : values.entrySet()) {
            Property property = type.getProperty(value.getKey());
            if (property == null) {
                continue;
            }
            for (Object candidate : property.getValues()) {
                if (String.valueOf(candidate).equalsIgnoreCase(value.getValue())) {
                    state = state.with(property, candidate);
                    break;
                }
            }
        }
        return state;
    }

}
