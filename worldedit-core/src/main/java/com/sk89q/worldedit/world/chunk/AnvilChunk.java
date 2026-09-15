/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.world.chunk;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.DataException;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.entity.EntityTypes;
import com.sk89q.worldedit.world.registry.LegacyMapper;
import com.sk89q.worldedit.world.storage.InvalidFormatException;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinTag;
import org.enginehub.linbus.tree.LinTagType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AnvilChunk implements Chunk {

    private final LinCompoundTag rootTag;
    private final byte[][] blocks;
    private final byte[][] blocksAdd;
    //FAWE start - EndlessIDs (1.7.10) stores id bits 12-15 in "BlocksB2Hi" and bits 16-23 in "BlocksB3"
    private final byte[][] blocksB2High;
    private final byte[][] blocksB3;
    //FAWE end
    private final byte[][] data;
    private final int rootX;
    private final int rootZ;

    private Map<BlockVector3, LinCompoundTag> tileEntities;

    /**
     * Construct the chunk with a compound tag.
     *
     * @param tag the tag to read
     * @throws DataException on a data error
     * @deprecated Use {@link #AnvilChunk(LinCompoundTag)}
     */
    @Deprecated
    public AnvilChunk(CompoundTag tag) throws DataException {
        this(tag.toLinTag());
    }

    /**
     * Construct the chunk with a compound tag.
     *
     * @param tag the tag to read
     * @throws DataException on a data error
     */
    public AnvilChunk(LinCompoundTag tag) throws DataException {
        rootTag = tag;

        rootX = rootTag.getTag("xPos", LinTagType.intTag()).value();
        rootZ = rootTag.getTag("zPos", LinTagType.intTag()).value();

        blocks = new byte[16][16 * 16 * 16];
        blocksAdd = new byte[16][16 * 16 * 8];
        //FAWE start
        blocksB2High = new byte[16][];
        blocksB3 = new byte[16][];
        //FAWE end
        data = new byte[16][16 * 16 * 8];

        LinListTag<LinTag<?>> sections = rootTag.getTag("Sections", LinTagType.listTag());

        for (LinTag<?> rawSectionTag : sections.value()) {
            if (!(rawSectionTag instanceof LinCompoundTag sectionTag)) {
                continue;
            }

            var sectionYTag = sectionTag.findTag("Y", LinTagType.byteTag());
            if (sectionYTag == null) {
                continue; // Empty section.
            }

            int y = sectionYTag.value();
            if (y < 0 || y >= 16) {
                continue;
            }

            blocks[y] = sectionTag.getTag("Blocks", LinTagType.byteArrayTag()).value();
            data[y] = sectionTag.getTag("Data", LinTagType.byteArrayTag()).value();

            // 4096 ID block support
            var addTag = sectionTag.findTag("Add", LinTagType.byteArrayTag());
            if (addTag != null) {
                blocksAdd[y] = addTag.value();
            }
            //FAWE start
            var b2HighTag = sectionTag.findTag("BlocksB2Hi", LinTagType.byteArrayTag());
            if (b2HighTag != null && b2HighTag.value().length == 16 * 16 * 8) {
                blocksB2High[y] = b2HighTag.value();
            }
            var b3Tag = sectionTag.findTag("BlocksB3", LinTagType.byteArrayTag());
            if (b3Tag != null && b3Tag.value().length == 16 * 16 * 16) {
                blocksB3[y] = b3Tag.value();
            }
            //FAWE end
        }

        int sectionsize = 16 * 16 * 16;
        for (byte[] block : blocks) {
            if (block.length != sectionsize) {
                throw new InvalidFormatException(
                        "Chunk blocks byte array expected " + "to be "
                                + sectionsize + " bytes; found "
                                + block.length);
            }
        }

        for (byte[] aData : data) {
            if (aData.length != (sectionsize / 2)) {
                throw new InvalidFormatException("Chunk block data byte array "
                        + "expected to be " + sectionsize + " bytes; found "
                        + aData.length);
            }
        }
    }

    private int getBlockID(BlockVector3 position) throws DataException {
        int x = position.x() - rootX * 16;
        int y = position.y();
        int z = position.z() - rootZ * 16;

        int section = y >> 4;
        if (section < 0 || section >= blocks.length) {
            throw new DataException("Chunk does not contain position " + position);
        }

        int yindex = y & 0x0F;

        int index = x + (z * 16 + (yindex * 16 * 16));

        try {
            // The block ID is the combination of the Blocks byte array with the
            // Add byte array. 'Blocks' stores the lowest 8 bits of a block's ID, and
            // 'Add' stores the highest 4 bits of the ID. The first block is stored
            // in the lowest nibble in the Add byte array.
            byte addByte = blocksAdd[section][index >> 1];
            int addId = (index & 1) == 0 ? (addByte & 0x0F) << 8 : (addByte & 0xF0) << 4;

            //FAWE start
            int id = (blocks[section][index] & 0xFF) + addId;
            if (blocksB2High[section] != null) {
                id |= nibble(blocksB2High[section], index) << 12;
            }
            if (blocksB3[section] != null) {
                id |= (blocksB3[section][index] & 0xFF) << 16;
            }
            return id;
            //FAWE end
        } catch (IndexOutOfBoundsException e) {
            throw new DataException("Chunk does not contain position " + position);
        }
    }

    //FAWE start
    private static int nibble(byte[] array, int index) {
        byte value = array[index >> 1];
        return (index & 1) == 0 ? value & 0x0F : (value & 0xF0) >> 4;
    }
    //FAWE end

    private int getBlockData(BlockVector3 position) throws DataException {
        int x = position.x() - rootX * 16;
        int y = position.y();
        int z = position.z() - rootZ * 16;

        int section = y >> 4;
        int yIndex = y & 0x0F;

        if (section < 0 || section >= blocks.length) {
            throw new DataException("Chunk does not contain position " + position);
        }

        int index = x + (z * 16 + (yIndex * 16 * 16));
        boolean shift = (index & 1) != 0;
        index >>= 2;

        try {
            byte dataByte = data[section][index];
            return shift ? (dataByte & 0xF0) >> 4 : dataByte & 0x0F;
        } catch (IndexOutOfBoundsException e) {
            throw new DataException("Chunk does not contain position " + position);
        }
    }

    /**
     * Used to load the tile entities.
     */
    private void populateTileEntities() {
        //FAWE start - 1.7.10 writes empty lists with element type END
        List<LinCompoundTag> tags = compoundList("TileEntities");
        //FAWE end

        tileEntities = new HashMap<>(tags.size());

        for (LinCompoundTag t : tags) {
            int x = 0;
            int y = 0;
            int z = 0;

            LinCompoundTag.Builder values = LinCompoundTag.builder();

            for (String key : t.value().keySet()) {
                LinTag<?> value = t.value().get(key);
                switch (key) {
                    case "x" -> {
                        if (value instanceof LinIntTag v) {
                            x = v.valueAsInt();
                        }
                    }
                    case "y" -> {
                        if (value instanceof LinIntTag v) {
                            y = v.valueAsInt();
                        }
                    }
                    case "z" -> {
                        if (value instanceof LinIntTag v) {
                            z = v.valueAsInt();
                        }
                    }
                    default -> {
                        // Do nothing.
                    }
                }

                values.put(key, value);
            }

            BlockVector3 vec = BlockVector3.at(x, y, z);
            tileEntities.put(vec, values.build());
        }
    }

    /**
     * Get the map of tags keyed to strings for a block's tile entity data. May
     * return null if there is no tile entity data. Not public yet because
     * what this function returns isn't ideal for usage.
     *
     * @param position the position
     * @return the compound tag for that position, which may be null
     * @throws DataException thrown if there is a data error
     */
    @Nullable
    private LinCompoundTag getBlockTileEntity(BlockVector3 position) throws DataException {
        if (tileEntities == null) {
            populateTileEntities();
        }

        return tileEntities.get(position);
    }

    //FAWE start - 1.7.10 biomes: vanilla "Biomes" (byte per column) or EndlessIDs "Biomes16v2" (little-endian short)
    private int[] biomes;

    @Override
    public BiomeType getBiome(BlockVector3 position) throws DataException {
        if (biomes == null) {
            biomes = new int[256];
            Arrays.fill(biomes, -1);
            var biomes16 = rootTag.findTag("Biomes16v2", LinTagType.byteArrayTag());
            var biomes8 = rootTag.findTag("Biomes", LinTagType.byteArrayTag());
            if (biomes16 != null && biomes16.value().length == 512) {
                byte[] raw = biomes16.value();
                for (int i = 0; i < 256; i++) {
                    biomes[i] = (raw[i * 2] & 0xFF) | (raw[i * 2 + 1] & 0xFF) << 8;
                }
            } else if (biomes8 != null && biomes8.value().length == 256) {
                byte[] raw = biomes8.value();
                for (int i = 0; i < 256; i++) {
                    biomes[i] = raw[i] & 0xFF;
                }
            }
        }
        int x = position.x() - rootX * 16;
        int z = position.z() - rootZ * 16;
        if (x < 0 || x >= 16 || z < 0 || z >= 16) {
            throw new DataException("Chunk does not contain position " + position);
        }
        int id = biomes[z * 16 + x];
        return id < 0 ? null : legacyBiome(id);
    }

    private List<BaseEntity> entities;

    /** A list of compounds; missing or empty lists (1.7.10 stores those with element type END) are empty. */
    @SuppressWarnings("unchecked")
    private List<LinCompoundTag> compoundList(String name) {
        LinListTag<?> list = rootTag.findTag(name, LinTagType.listTag());
        if (list == null || list.value().isEmpty()) {
            return List.of();
        }
        return list.asTypeChecked(LinTagType.compoundTag()).value();
    }

    @Override
    public List<BaseEntity> getEntities() throws DataException {
        if (entities == null) {
            List<BaseEntity> list = new ArrayList<>();
            for (LinCompoundTag tag : compoundList("Entities")) {
                var idTag = tag.findTag("id", LinTagType.stringTag());
                EntityType type = idTag == null ? null : EntityTypes.get(legacyEntityId(idTag.value()));
                if (type != null) {
                    list.add(new BaseEntity(type, LazyReference.computed(tag)));
                }
            }
            entities = list;
        }
        return entities;
    }

    /**
     * 1.7.10 entity names ("Pig", "RTM.Train") to namespaced ids ("minecraft:pig", "rtm:train").
     */
    private static String legacyEntityId(String name) {
        if (name.indexOf(':') >= 0) {
            return name.toLowerCase(Locale.ROOT);
        }
        String namespace = "minecraft";
        String path = name;
        int dot = name.indexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            namespace = name.substring(0, dot);
            path = name.substring(dot + 1);
        }
        return cleanId(namespace) + ":" + cleanId(path);
    }

    private static String cleanId(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]+", "_");
    }

    @Nullable
    private static BiomeType legacyBiome(int legacyId) {
        for (BiomeType type : BiomeType.REGISTRY.values()) {
            if (type.getLegacyId() == legacyId) {
                return type;
            }
        }
        return null;
    }
    //FAWE end

    @Override
    public BaseBlock getBlock(BlockVector3 position) throws DataException {
        int id = getBlockID(position);
        int data = getBlockData(position);

        BlockState state = LegacyMapper.getInstance().getBlockFromLegacy(id, data);
        if (state == null) {
            WorldEdit.logger.warn("Unknown legacy block " + id + ":" + data + " found when loading legacy anvil chunk.");
            return BlockTypes.AIR.getDefaultState().toBaseBlock();
        }
        LinCompoundTag tileEntity = getBlockTileEntity(position);

        if (tileEntity != null) {
            return state.toBaseBlock(tileEntity);
        }

        return state.toBaseBlock();
    }

}
