package com.fastasyncworldedit.forge1710;

import com.fastasyncworldedit.forge1710.entity.Forge1710Player;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.internal.cui.CUIEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * WorldEditCUI support over the "WECUI" plugin channel used by the 1.7.10 client mod: the client announces itself with
 * {@code v|<version>}, the server answers with selection shape and point messages ({@code type|param|param...}).
 */
public final class Forge1710CUI {

    public static final String CHANNEL = "WECUI";

    @Nullable
    private static FMLEventChannel channel;

    private Forge1710CUI() {
    }

    static void register() {
        if (channel == null) {
            channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
            channel.register(new Forge1710CUI());
        }
    }

    public static void send(EntityPlayerMP player, CUIEvent event) {
        FMLEventChannel local = channel;
        if (local == null) {
            return;
        }
        StringBuilder message = new StringBuilder(event.getTypeId());
        for (String parameter : event.getParameters()) {
            message.append('|').append(parameter);
        }
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        local.sendTo(new FMLProxyPacket(Unpooled.wrappedBuffer(bytes), CHANNEL), player);
    }

    @SubscribeEvent
    public void onClientMessage(FMLNetworkEvent.ServerCustomPacketEvent event) {
        if (!(event.handler instanceof NetHandlerPlayServer handler)) {
            return;
        }
        EntityPlayerMP player = handler.playerEntity;
        ByteBuf payload = event.packet.payload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        Forge1710Player actor = Forge1710Adapter.adapt(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
        session.handleCUIInitializationMessage(text, actor);
    }

}
