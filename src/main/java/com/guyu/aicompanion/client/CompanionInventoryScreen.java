package com.guyu.aicompanion.client;

import com.guyu.aicompanion.menu.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * AI 同伴背包 GUI 界面 — 复用原版箱子纹理 {@code generic_54.png}。
 * <p>
 * 布局与 3 行箱子完全一致：上方 27 格为同伴背包，下方为玩家背包 + 快捷栏。
 * <p>
 * 此类仅由 {@code AICompanionClient}（{@code @Mod(dist = Dist.CLIENT)}）引用，
 * 不会在专用服务器上加载，因此无需 {@code @OnlyIn} 注解。
 */
public class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {

    /** 复用原版箱子的 GUI 纹理 */
    private static final Identifier CONTAINER_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

    /** 3 行箱子布局的高度：114 + 3×18 = 168 */
    private static final int IMAGE_HEIGHT = 168;

    public CompanionInventoryScreen(CompanionInventoryMenu menu,
                                     Inventory playerInv,
                                     Component title) {
        super(menu, playerInv, title, 176, IMAGE_HEIGHT);

        // 标题位置（左上角）
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        // 玩家背包标签位置（与 3 行箱子一致）
        this.inventoryLabelX = 8;
        this.inventoryLabelY = IMAGE_HEIGHT - 94;  // = 74
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        // 调用父类绘制半透明背景模糊效果
        super.extractBackground(graphics, mouseX, mouseY, partialTick);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // 绘制箱子上半部分（标题 + 容器格子区域）
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_BACKGROUND,
                x, y, 0.0F, 0.0F,
                this.imageWidth, 71, 256, 256);

        // 绘制箱子下半部分（玩家背包 + 快捷栏区域）
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_BACKGROUND,
                x, y + 71, 0.0F, 126.0F,
                this.imageWidth, 96, 256, 256);
    }
}
