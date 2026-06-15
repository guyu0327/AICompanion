package com.guyu.aicompanion;

import com.guyu.aicompanion.entity.AICompanionRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = AICompanion.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AICompanion.MODID, value = Dist.CLIENT)
public class AICompanionClient {
    public AICompanionClient(ModContainer container) {
        // Register CLIENT config - each player has their own API key and settings
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        AICompanion.LOGGER.info("HELLO FROM CLIENT SETUP");
        AICompanion.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AICompanion.COMPANION.get(), AICompanionRenderer::new);
    }
}
