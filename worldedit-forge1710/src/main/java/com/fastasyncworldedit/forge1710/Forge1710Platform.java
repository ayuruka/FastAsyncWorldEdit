package com.fastasyncworldedit.forge1710;

import com.fastasyncworldedit.forge1710.world.Forge1710Relighter;
import com.fastasyncworldedit.core.extent.processor.lighting.RelighterFactory;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.forge1710.entity.Forge1710Player;
import com.fastasyncworldedit.forge1710.registry.Forge1710Registries;
import com.fastasyncworldedit.forge1710.world.Forge1710World;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.platform.AbstractPlatform;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Preference;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.registry.Registries;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.enginehub.piston.Command;
import org.enginehub.piston.CommandManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Forge1710Platform extends AbstractPlatform {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");

    private final FaweForge1710Mod mod;
    private boolean hookingEvents;

    Forge1710Platform(FaweForge1710Mod mod) {
        this.mod = mod;
    }

    boolean isHookingEvents() {
        return hookingEvents;
    }

    @Override
    public Registries getRegistries() {
        return Forge1710Registries.getInstance();
    }

    @Override
    public int getDataVersion() {
        // 1.7.10 predates data versions; FAWE only uses this to tag schematics.
        return Constants.DATA_VERSION_MC_1_13;
    }

    @Override
    public DataFixer getDataFixer() {
        return null;
    }

    @Override
    public boolean isValidMobType(String type) {
        return EntityList.stringToClassMapping.containsKey(type);
    }

    @Override
    public void reload() {
        getConfiguration().load();
    }

    @Override
    public int schedule(long delay, long period, Runnable task) {
        if (period > 0) {
            return TaskManager.taskManager().repeat(task, (int) period);
        }
        TaskManager.taskManager().later(task, (int) delay);
        return -1;
    }

    @Override
    public List<? extends World> getWorlds() {
        MinecraftServer server = MinecraftServer.getServer();
        List<World> worlds = new ArrayList<>();
        if (server != null && server.worldServers != null) {
            for (WorldServer world : server.worldServers) {
                if (world != null) {
                    worlds.add(Forge1710Adapter.adapt(world));
                }
            }
        }
        return worlds;
    }

    @Nullable
    @Override
    public Player matchPlayer(Player player) {
        if (player instanceof Forge1710Player) {
            return player;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return null;
        }
        for (Object o : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP entity = (EntityPlayerMP) o;
            if (entity.getCommandSenderName().equals(player.getName())) {
                return Forge1710Adapter.adapt(entity);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public World matchWorld(World world) {
        if (world instanceof Forge1710World) {
            return world;
        }
        for (World candidate : getWorlds()) {
            if (candidate.getName().equals(world.getName())) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void registerCommands(CommandManager manager) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !(server.getCommandManager() instanceof ServerCommandManager commands)) {
            LOGGER.warn("No server command manager available; WorldEdit commands were not registered");
            return;
        }
        List<Command> all = manager.getAllCommands().collect(Collectors.toList());
        Set<String> taken = new HashSet<>();
        for (Object name : commands.getCommands().keySet()) {
            taken.add(((String) name).toLowerCase(Locale.ROOT));
        }
        for (Command command : all) {
            taken.add(command.getName().toLowerCase(Locale.ROOT));
            command.getAliases().forEach(alias -> taken.add(alias.toLowerCase(Locale.ROOT)));
        }
        int slashAliases = 0;
        for (Command command : all) {
            // Bukkit hybrids (Crucible/Thermos) pass "//wand" to the vanilla handler as "/wand", which strips one more
            // slash and looks up "wand". Register the single-slash name as an alias when nothing else uses it, so
            // "//wand" works on hybrids as well as on plain Forge.
            List<String> extra = new ArrayList<>();
            List<String> names = new ArrayList<>(command.getAliases());
            names.add(command.getName());
            for (String name : names) {
                if (name.startsWith("/") && name.length() > 1) {
                    String stripped = name.substring(1).toLowerCase(Locale.ROOT);
                    if (taken.add(stripped)) {
                        extra.add(stripped);
                    }
                }
            }
            slashAliases += extra.size();
            commands.registerCommand(new Forge1710CommandWrapper(command, extra));
        }
        LOGGER.info("Registered {} WorldEdit commands ({} single-slash aliases)", all.size(), slashAliases);
    }

    @Override
    public void setGameHooksEnabled(boolean enabled) {
        this.hookingEvents = enabled;
    }

    @Override
    public Forge1710Configuration getConfiguration() {
        return mod.getConfig();
    }

    @Override
    public String getVersion() {
        return FaweForge1710Mod.VERSION;
    }

    @Override
    public String getPlatformName() {
        return "Forge-1.7.10";
    }

    @Override
    public String getPlatformVersion() {
        return FaweForge1710Mod.VERSION;
    }

    @Override
    public Map<Capability, Preference> getCapabilities() {
        Map<Capability, Preference> capabilities = new EnumMap<>(Capability.class);
        capabilities.put(Capability.CONFIGURATION, Preference.PREFER_OTHERS);
        capabilities.put(Capability.GAME_HOOKS, Preference.NORMAL);
        capabilities.put(Capability.PERMISSIONS, Preference.NORMAL);
        capabilities.put(Capability.USER_COMMANDS, Preference.NORMAL);
        capabilities.put(Capability.WORLD_EDITING, Preference.PREFERRED);
        // PlatformManager.createProxyActor queries this for every player action, even without a CUI client mod.
        // AbstractPlayerActor.dispatchCUIEvent is a no-op, so CUI events are simply dropped.
        capabilities.put(Capability.WORLDEDIT_CUI, Preference.NORMAL);
        return capabilities;
    }

    @Override
    public Set<SideEffect> getSupportedSideEffects() {
        return EnumSet.of(SideEffect.NEIGHBORS, SideEffect.LIGHTING);
    }

    @Override
    public @Nonnull RelighterFactory getRelighterFactory() {
        return (mode, world, queue) -> new Forge1710Relighter(world);
    }

    @Override
    public int versionMinY() {
        return 0;
    }

    @Override
    public int versionMaxY() {
        return 255;
    }

}
