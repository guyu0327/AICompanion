package com.guyu.aicompanion.entity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * AI 同伴实体渲染器 — 使用 Steve 玩家皮肤。
 * <p>
 * 添加 {@link ItemInHandLayer} 以渲染主手/副手物品；
 * 重写 {@link #extractRenderState} 调用 {@link HumanoidMobRenderer#extractHumanoidRenderState}
 * 填充手持物品、手臂姿态等数据到渲染状态。
 */
public class AICompanionRenderer extends LivingEntityRenderer<AICompanionEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

    private static final Identifier STEVE_SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public AICompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        // 添加手持物品渲染层 — 使主手/副手物品可见
        this.addLayer(new ItemInHandLayer<>(this));
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return STEVE_SKIN;
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    /**
     * 提取渲染状态 — 填充手持物品、手臂姿态、游泳/滑翔等数据。
     * 调用 {@link HumanoidMobRenderer#extractHumanoidRenderState} 完成人形实体的标准状态提取。
     */
    @Override
    public void extractRenderState(AICompanionEntity entity, HumanoidRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(
                entity, state, partialTicks, this.itemModelResolver);
    }
}
