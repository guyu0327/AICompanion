package com.guyu.aicompanion;

import com.guyu.aicompanion.entity.AICompanionRenderer;
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

// 此类不会在专用服务器上加载，从这里访问客户端代码是安全的
@Mod(value = AICompanion.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AICompanion.MODID, value = Dist.CLIENT)
public class AICompanionClient {
    public AICompanionClient(ModContainer container) {
        // 注册 CLIENT 配置 — 每个玩家有自己的 API key 和设置
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        // 允许 NeoForge 为本 mod 的配置创建配置界面
        // 配置界面路径：Mods 界面 > 点击本 mod > 点击 Config
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AICompanion.COMPANION.get(), AICompanionRenderer::new);
    }
}
