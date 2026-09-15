package com.fastasyncworldedit.forge1710.entity;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.forge1710.Forge1710Adapter;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.WorldEditText;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.serializer.legacy.LegacyComponentSerializer;
import com.sk89q.worldedit.world.World;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer;
import com.sk89q.worldedit.util.formatting.text.serializer.plain.PlainComponentSerializer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public class Forge1710Player extends AbstractPlayerActor {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");
    /**
     * Mirrors every message sent to a player into the server log, so problems can be diagnosed from the log alone.
     * On by default while the port is being tested; disable with -Dfawe.forge1710.logchat=false.
     */
    private static final boolean LOG_CHAT = Boolean.parseBoolean(System.getProperty("fawe.forge1710.logchat", "true"));

    // EnumChatFormatting.getTextWithoutFormattingCodes is client-only in 1.7.10.
    private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");

    private final EntityPlayerMP player;

    public Forge1710Player(EntityPlayerMP player) {
        this.player = player;
    }

    public EntityPlayerMP getPlayer() {
        return player;
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueID();
    }

    @Override
    public String getName() {
        return player.getCommandSenderName();
    }

    @Override
    public BaseItemStack getItemInHand(HandSide handSide) {
        if (handSide != HandSide.MAIN_HAND) {
            return Forge1710Adapter.adapt((net.minecraft.item.ItemStack) null);
        }
        return Forge1710Adapter.adapt(player.getCurrentEquippedItem());
    }

    @Override
    public void giveItem(BaseItemStack itemStack) {
        net.minecraft.item.ItemStack stack = Forge1710Adapter.toNative(itemStack);
        if (stack != null) {
            runSync(() -> player.inventory.addItemStackToInventory(stack));
        }
    }

    @Override
    public BlockBag getInventoryBlockBag() {
        return null;
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
    }

    @Override
    public boolean setLocation(Location location) {
        runSync(() -> player.playerNetServerHandler.setPlayerLocation(
                location.x(), location.y(), location.z(), location.getYaw(), location.getPitch()));
        return true;
    }

    @Override
    public World getWorld() {
        return Forge1710Adapter.adapt((WorldServer) player.worldObj);
    }

    @Override
    public BaseEntity getState() {
        throw new UnsupportedOperationException("Cannot create a state from this object");
    }

    @Override
    public void printRaw(String msg) {
        send(msg, null);
    }

    @Override
    public void printDebug(String msg) {
        send(msg, EnumChatFormatting.GRAY);
    }

    @Override
    public void print(String msg) {
        send(msg, EnumChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public void printError(String msg) {
        send(msg, EnumChatFormatting.RED);
    }

    @Override
    public void print(Component component) {
        Component rendered = WorldEditText.format(component, getLocale());
        if (LOG_CHAT) {
            LOGGER.info("[-> {}] {}", getName(), PlainComponentSerializer.INSTANCE.serialize(rendered));
        }
        IChatComponent chat;
        try {
            // WorldEdit's text library writes the same JSON dialect as 1.7.10 (hoverEvent "value"), so colours, hover
            // text and click actions survive.
            chat = IChatComponent.Serializer.func_150699_a(GsonComponentSerializer.INSTANCE.serialize(rendered));
        } catch (RuntimeException e) {
            chat = new ChatComponentText(LegacyComponentSerializer.legacy().serialize(rendered));
        }
        IChatComponent message = chat;
        runSync(() -> player.addChatMessage(message));
    }

    @Override
    public void sendTitle(Component title, Component sub) {
        print(title);
        print(sub);
    }

    private void send(String msg, @Nullable EnumChatFormatting color) {
        if (LOG_CHAT) {
            LOGGER.info("[-> {}] {}", getName(), FORMATTING_CODE.matcher(msg).replaceAll(""));
        }
        for (String part : msg.split("\n")) {
            ChatComponentText text = new ChatComponentText(part);
            if (color != null) {
                text.getChatStyle().setColor(color);
            }
            runSync(() -> player.addChatMessage(text));
        }
    }

    private static void runSync(Runnable runnable) {
        if (Fawe.isMainThread()) {
            runnable.run();
        } else {
            TaskManager.taskManager().task(runnable);
        }
    }

    @Override
    public Locale getLocale() {
        // The client reports its language (e.g. "ja_JP") in C15PacketClientSettings.
        String language = null;
        try {
            language = ReflectionHelper.getPrivateValue(EntityPlayerMP.class, player, "translator", "field_71148_cg");
        } catch (RuntimeException ignored) {
        }
        if (language == null || language.isEmpty()) {
            return WorldEdit.getInstance().getConfiguration().defaultLocale;
        }
        return Locale.forLanguageTag(language.replace('_', '-'));
    }

    @Override
    public String[] getGroups() {
        return new String[0];
    }

    @Override
    public boolean hasPermission(String permission) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return false;
        }
        // Operators (and single-player owners with cheats) get every WorldEdit permission.
        return player.canCommandSenderUseCommand(2, "worldedit");
    }

    @Override
    public void setPermission(String permission, boolean value) {
    }

    @Nullable
    @Override
    public <T> T getFacet(Class<? extends T> cls) {
        return null;
    }

    @Override
    public SessionKey getSessionKey() {
        return new SessionKeyImpl(player.getUniqueID(), player.getCommandSenderName());
    }

    private record SessionKeyImpl(UUID uuid, String name) implements SessionKey {

        @Override
        public UUID getUniqueId() {
            return uuid;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isActive() {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                return false;
            }
            for (Object o : server.getConfigurationManager().playerEntityList) {
                if (((EntityPlayerMP) o).getUniqueID().equals(uuid)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

    }

}
