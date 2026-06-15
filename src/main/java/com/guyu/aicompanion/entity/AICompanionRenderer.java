package com.guyu.aicompanion.entity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

public class AICompanionRenderer extends LivingEntityRenderer<AICompanionEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

    private static final Identifier STEVE_SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public AICompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return STEVE_SKIN;
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }
}
