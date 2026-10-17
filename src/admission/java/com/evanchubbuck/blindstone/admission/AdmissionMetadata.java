package com.evanchubbuck.blindstone.admission;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Extract wire IDs from the pinned runtime, without a hand-maintained protocol-number table. */
public final class AdmissionMetadata {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minecraft", SharedConstants.getCurrentVersion().name());
        result.put("protocol", SharedConstants.getCurrentVersion().protocolVersion());
        result.put("login_clientbound", packets(LoginProtocols.CLIENTBOUND_TEMPLATE));
        result.put("login_serverbound", packets(LoginProtocols.SERVERBOUND_TEMPLATE));
        result.put("configuration_clientbound", packets(ConfigurationProtocols.CLIENTBOUND_TEMPLATE));
        result.put("configuration_serverbound", packets(ConfigurationProtocols.SERVERBOUND_TEMPLATE));
        result.put("play_clientbound", packets(GameProtocols.CLIENTBOUND_TEMPLATE));
        result.put("play_serverbound", packets(GameProtocols.SERVERBOUND_TEMPLATE));
        result.put("blocks", Map.of("air", Block.getId(Blocks.AIR.defaultBlockState()),
                "stone", Block.getId(Blocks.STONE.defaultBlockState()),
                "deepslate", Block.getId(Blocks.DEEPSLATE.defaultBlockState()),
                "diamond_ore", Block.getId(Blocks.DIAMOND_ORE.defaultBlockState())));
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
    }

    private static Map<String, Integer> packets(ProtocolInfo.DetailsProvider provider) {
        Map<String, Integer> result = new LinkedHashMap<>();
        provider.details().listPackets((type, id) -> result.put(type.id().getPath(), id));
        return result;
    }
}
