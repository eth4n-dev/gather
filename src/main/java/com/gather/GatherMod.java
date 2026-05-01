package com.gather;

import com.gather.network.GatherNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatherMod implements ModInitializer {

    public static final String MOD_ID = "gather";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
                LOGGER.info("Gather Dark Mode resource pack registered: {}",
                ResourceLoader.registerBuiltinPack(
                        Identifier.of(MOD_ID, "dark_mode"),
                        container,
                        Text.literal("Gather Dark Mode"),
                        PackActivationType.NORMAL)));

        GatherNetworking.registerServerSide();
    }
}
