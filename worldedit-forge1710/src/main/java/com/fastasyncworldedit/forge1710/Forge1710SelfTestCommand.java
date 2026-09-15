package com.fastasyncworldedit.forge1710;

import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.forge1710.registry.NativeBlockMapper;
import com.fastasyncworldedit.forge1710.world.Forge1710World;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * Console-only self test used during porting ({@code -Dfawe.forge1710.selftest=true}). Edits a region high above spawn
 * through the full FAWE queue, verifies the native world, then undoes and verifies again.
 */
public class Forge1710SelfTestCommand extends CommandBase {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-SelfTest");

    @Override
    public String getCommandName() {
        return "faweselftest";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/faweselftest [modBlockId] | /faweselftest native <x1> <y1> <z1> <x2> <y2> <z2>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 7 && args[0].equals("native")) {
            nativeCount(sender, args);
            return;
        }
        if (args.length == 4 && args[0].equals("tile")) {
            reply(sender, tileInfo(sender, args));
            return;
        }
        if (args.length == 7 && args[0].equals("entities")) {
            reply(sender, entityInfo(sender, args));
            return;
        }
        if (args.length == 1 && args[0].equals("pos") && sender instanceof net.minecraft.entity.player.EntityPlayerMP player) {
            reply(sender, String.format(java.util.Locale.ROOT, "[SELFTEST] pos %.2f %.2f %.2f (FAWE: %s)", player.posX,
                    player.posY, player.posZ, Forge1710Adapter.adapt(player).getLocation().toVector()));
            return;
        }
        if (args.length == 3 && args[0].equals("biome")) {
            int x = Integer.parseInt(args[1]);
            int z = Integer.parseInt(args[2]);
            net.minecraft.world.biome.BiomeGenBase biome = sender.getEntityWorld().getBiomeGenForCoords(x, z);
            reply(sender, "[SELFTEST] biome " + x + " " + z + ": " + (biome == null ? "null" : biome.biomeName));
            return;
        }
        String modBlock = args.length > 0 ? args[0] : null;
        TaskManager.taskManager().async(() -> run(modBlock));
    }

    /**
     * Counts blocks straight from the Minecraft world (bypassing FAWE), so automated tests can check what an edit
     * really wrote. Runs on the server thread. Replies with "[SELFTEST] native <volume>: id:meta=count, ...".
     */
    private static void nativeCount(ICommandSender sender, String[] args) {
        int x1 = Integer.parseInt(args[1]);
        int y1 = Integer.parseInt(args[2]);
        int z1 = Integer.parseInt(args[3]);
        int x2 = Integer.parseInt(args[4]);
        int y2 = Integer.parseInt(args[5]);
        int z2 = Integer.parseInt(args[6]);
        net.minecraft.world.World world = sender.getEntityWorld();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        int volume = 0;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    Block block = world.getBlock(x, y, z);
                    String key = Block.blockRegistry.getNameForObject(block) + ":" + world.getBlockMetadata(x, y, z);
                    counts.merge(key, 1, Integer::sum);
                    volume++;
                }
            }
        }
        StringBuilder text = new StringBuilder("[SELFTEST] native ").append(volume).append(':');
        counts.forEach((key, count) -> text.append(' ').append(key).append('=').append(count));
        LOGGER.info(text.toString());
        sender.addChatMessage(new net.minecraft.util.ChatComponentText(text.toString()));
    }

    private static void reply(ICommandSender sender, String text) {
        LOGGER.info(text);
        sender.addChatMessage(new net.minecraft.util.ChatComponentText(text));
    }

    /** "[SELFTEST] tile x y z: <NBT>" or "none". */
    private static String tileInfo(ICommandSender sender, String[] args) {
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int z = Integer.parseInt(args[3]);
        net.minecraft.tileentity.TileEntity tile = sender.getEntityWorld().getTileEntity(x, y, z);
        String prefix = "[SELFTEST] tile " + x + " " + y + " " + z + ": ";
        if (tile == null) {
            return prefix + "none";
        }
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tile.writeToNBT(tag);
        return prefix + tag;
    }

    /** "[SELFTEST] entities <n>: Name=count ..." for living, non-player entities whose block position is inside. */
    private static String entityInfo(ICommandSender sender, String[] args) {
        int x1 = Integer.parseInt(args[1]);
        int y1 = Integer.parseInt(args[2]);
        int z1 = Integer.parseInt(args[3]);
        int x2 = Integer.parseInt(args[4]);
        int y2 = Integer.parseInt(args[5]);
        int z2 = Integer.parseInt(args[6]);
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        int total = 0;
        for (Object o : sender.getEntityWorld().loadedEntityList) {
            net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) o;
            if (entity.isDead || entity instanceof net.minecraft.entity.player.EntityPlayer) {
                continue;
            }
            int ex = (int) Math.floor(entity.posX);
            int ey = (int) Math.floor(entity.posY);
            int ez = (int) Math.floor(entity.posZ);
            if (ex < Math.min(x1, x2) || ex > Math.max(x1, x2) || ey < Math.min(y1, y2) || ey > Math.max(y1, y2)
                    || ez < Math.min(z1, z2) || ez > Math.max(z1, z2)) {
                continue;
            }
            counts.merge(String.valueOf(net.minecraft.entity.EntityList.getEntityString(entity)), 1, Integer::sum);
            total++;
        }
        StringBuilder text = new StringBuilder("[SELFTEST] entities ").append(total).append(':');
        counts.forEach((name, count) -> text.append(' ').append(name).append('=').append(count));
        return text.toString();
    }

    private static <T> T sync(Supplier<T> supplier) {
        return TaskManager.taskManager().sync(supplier);
    }

    private static void run(String modBlockArg) {
        try {
            WorldServer nmsWorld = MinecraftServer.getServer().worldServers[0];
            Forge1710World world = Forge1710Adapter.adapt(nmsWorld);
            ChunkCoordinates spawn = sync(nmsWorld::getSpawnPoint);
            int cx = (spawn.posX >> 4) << 4;
            int cz = (spawn.posZ >> 4) << 4;
            // Two chunks wide and crossing a section boundary (y 200..215 is one section, 216..219 the next).
            CuboidRegion region = new CuboidRegion(world, BlockVector3.at(cx, 200, cz), BlockVector3.at(cx + 31, 219, cz + 15));
            BlockState stone = BlockTypes.get("minecraft:stone").getDefaultState();

            long start = System.nanoTime();
            EditSession edit = WorldEdit.getInstance().newEditSessionBuilder().world(world).limitUnlimited().build();
            edit.setBlocks((Region) region, stone);
            edit.close();
            long millis = (System.nanoTime() - start) / 1_000_000;
            int changed = edit.getBlockChangeCount();
            int stoneMismatch = sync(() -> countMismatch(nmsWorld, region, Block.getBlockById(1), 0));
            LOGGER.info("[SELFTEST] set stone: changed={} volume={} mismatches={} time={}ms -> {}",
                    changed, region.getVolume(), stoneMismatch, millis, stoneMismatch == 0 ? "PASS" : "FAIL");

            if (modBlockArg != null) {
                BlockState modState = BlockTypes.get(modBlockArg.toLowerCase()).getDefaultState()
                        .with(BlockTypes.get(modBlockArg.toLowerCase()).getProperty(NativeBlockMapper.META_PROPERTY_NAME), 3);
                CuboidRegion small = new CuboidRegion(world, BlockVector3.at(cx + 2, 205, cz + 2), BlockVector3.at(cx + 5, 208, cz + 5));
                EditSession modEdit = WorldEdit.getInstance().newEditSessionBuilder().world(world).limitUnlimited().build();
                modEdit.setBlocks((Region) small, modState);
                modEdit.close();
                Block modNative = NativeBlockMapper.get().getBlock(modBlockArg.toLowerCase());
                int modMismatch = sync(() -> countMismatch(nmsWorld, small, modNative, 3));
                LOGGER.info("[SELFTEST] set {}[legacy_meta=3]: mismatches={} -> {}", modBlockArg, modMismatch,
                        modMismatch == 0 ? "PASS" : "FAIL");
                EditSession modUndo = WorldEdit.getInstance().newEditSessionBuilder().world(world).limitUnlimited().build();
                modEdit.undo(modUndo);
                modUndo.close();
            }

            EditSession undo = WorldEdit.getInstance().newEditSessionBuilder().world(world).limitUnlimited().build();
            edit.undo(undo);
            undo.close();
            int airMismatch = sync(() -> countMismatch(nmsWorld, region, Block.getBlockById(0), -1));
            LOGGER.info("[SELFTEST] undo: mismatches={} -> {}", airMismatch, airMismatch == 0 ? "PASS" : "FAIL");

            BlockState read = world.getBlock(BlockVector3.at(cx, 1, cz));
            LOGGER.info("[SELFTEST] read back bedrock layer at y=1: {}", read);
        } catch (Throwable t) {
            LOGGER.error("[SELFTEST] FAILED with exception", t);
        }
    }

    /**
     * Server thread only. {@code meta < 0} ignores metadata.
     */
    private static int countMismatch(WorldServer world, CuboidRegion region, Block expected, int meta) {
        int mismatches = 0;
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    if (world.getBlock(x, y, z) != expected || (meta >= 0 && world.getBlockMetadata(x, y, z) != meta)) {
                        mismatches++;
                    }
                }
            }
        }
        return mismatches;
    }

}
