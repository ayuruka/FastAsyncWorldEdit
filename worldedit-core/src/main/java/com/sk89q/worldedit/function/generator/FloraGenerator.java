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

package com.sk89q.worldedit.function.generator;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.function.RegionFunction;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

/**
 * Generates flora (which may include tall grass, flowers, etc.).
 *
 * <p>The current implementation is not biome-aware, but it may become so in
 * the future.</p>
 */
public class FloraGenerator implements RegionFunction {

    private final EditSession editSession;
    private final boolean biomeAware = false;
    private final Pattern desertPattern = getDesertPattern();
    private final Pattern temperatePattern = getTemperatePattern();
    //FAWE start
    private final Pattern mushroomPattern = mushroomPattern();
    private final Pattern netherPattern = netherPattern();
    private final Pattern warpedNyliumPattern = warpedNyliumPattern();
    //FAWE end

    /**
     * Create a new flora generator.
     *
     * @param editSession the edit session
     */
    public FloraGenerator(EditSession editSession) {
        this.editSession = editSession;
    }

    /**
     * Return whether the flora generator is set to be biome-aware.
     *
     * <p>By default, it is currently disabled by default, but
     * this may change.</p>
     *
     * @return true if biome aware
     */
    public boolean isBiomeAware() {
        return biomeAware;
    }

    /**
     * Set whether the generator is biome aware.
     *
     * <p>It is currently not possible to make the generator biome-aware.</p>
     *
     * @param biomeAware must always be false
     */
    public void setBiomeAware(boolean biomeAware) {
        if (biomeAware) {
            throw new IllegalArgumentException("Cannot enable biome-aware mode; not yet implemented");
        }
    }

    /**
     * Get a pattern for plants to place inside a desert environment.
     *
     * @return a pattern that places flora
     */
    public static Pattern getDesertPattern() {
        RandomPattern pattern = new RandomPattern();
        addIfPresent(pattern, BlockTypes.DEAD_BUSH, 30);
        addIfPresent(pattern, BlockTypes.CACTUS, 20);
        addIfPresent(pattern, BlockTypes.AIR, 300);
        return pattern;
    }

    /**
     * Get a pattern for plants to place inside a temperate environment.
     *
     * @return a pattern that places flora
     */
    public static Pattern getTemperatePattern() {
        RandomPattern pattern = new RandomPattern();
        BlockType grass = BlockTypes.SHORT_GRASS;
        addIfPresent(pattern, grass, 300);
        addIfPresent(pattern, BlockTypes.POPPY, 5);
        addIfPresent(pattern, BlockTypes.DANDELION, 5);
        return pattern;
    }

    //FAWE start
    /**
     * Get a pattern for plants to place inside a mushroom environment.
     *
     * @return a pattern that places flora
     */
    public static Pattern mushroomPattern() {
        RandomPattern pattern = new RandomPattern();
        addIfPresent(pattern, BlockTypes.RED_MUSHROOM, 10);
        addIfPresent(pattern, BlockTypes.BROWN_MUSHROOM, 10);
        return pattern;
    }

    /**
     * Get a pattern for plants to place inside a nether environment.
     *
     * @return a pattern that places flora
     */
    public static Pattern netherPattern() {
        RandomPattern pattern = new RandomPattern();
        addIfPresent(pattern, BlockTypes.CRIMSON_ROOTS, 10);
        addIfPresent(pattern, BlockTypes.CRIMSON_FUNGUS, 20);
        addIfPresent(pattern, BlockTypes.WARPED_FUNGUS, 5);
        return pattern;
    }

    /**
     * Get a pattern for plants to place inside a nether environment.
     *
     * @return a pattern that places flora
     */
    public static Pattern warpedNyliumPattern() {
        RandomPattern pattern = new RandomPattern();
        addIfPresent(pattern, BlockTypes.WARPED_ROOTS, 15);
        addIfPresent(pattern, BlockTypes.NETHER_SPROUTS, 20);
        addIfPresent(pattern, BlockTypes.WARPED_FUNGUS, 7);
        addIfPresent(pattern, BlockTypes.CRIMSON_ROOTS, 10);
        return pattern;
    }
    //FAWE end

    @Override
    public boolean apply(BlockVector3 position) throws WorldEditException {
        //FAWE start
        int dataVersion = WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getDataVersion();
        //FAWE end
        BlockState block = editSession.getBlock(position);

        if (block.getBlockType() == BlockTypes.GRASS_BLOCK) {
            editSession.setBlock(position.add(0, 1, 0), temperatePattern.applyBlock(position));
            return true;
        //FAWE start - add red sand
        } else if (block.getBlockType() == BlockTypes.SAND || block.getBlockType() == BlockTypes.RED_SAND) {
        //FAWE end
            editSession.setBlock(position.add(0, 1, 0), desertPattern.applyBlock(position));
            return true;
        //FAWE start - add new types
        } else if (block.getBlockType() == BlockTypes.MYCELIUM || block.getBlockType() == BlockTypes.NETHERRACK) {
            editSession.setBlock(position.add(0, 1, 0), mushroomPattern.applyBlock(position));
            return true;
        } else if (dataVersion >= Constants.DATA_VERSION_MC_1_16) {
            if (block.getBlockType() == BlockTypes.SOUL_SOIL || block.getBlockType() == BlockTypes.CRIMSON_NYLIUM) {
                editSession.setBlock(position.add(0, 1, 0), netherPattern.applyBlock(position));
                return true;
            } else if (block.getBlockType() == BlockTypes.WARPED_NYLIUM) {
                editSession.setBlock(position.add(0, 1, 0), warpedNyliumPattern.applyBlock(position));
            }
        }
        //FAWE end

        return false;
    }

    //FAWE start - platforms without newer blocks (e.g. Minecraft 1.7.10) have null BlockTypes constants
    private static void addIfPresent(RandomPattern pattern, @javax.annotation.Nullable com.sk89q.worldedit.world.block.BlockType type, double weight) {
        if (type != null) {
            pattern.add(type.getDefaultState(), weight);
        }
    }
    //FAWE end

}
