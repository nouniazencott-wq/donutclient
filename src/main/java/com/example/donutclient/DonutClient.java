package com.example.donutclient;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. This mod is client-only ("environment": "client" in
 * fabric.mod.json), so almost everything lives in src/client. This class just
 * holds the shared constants.
 */
public class DonutClient implements ModInitializer {
    public static final String MOD_ID = "donutclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("DonutClient common init");
    }
}
