package com.guyu.aicompanion.entity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * AI 同伴实体渲染器 — 使用 Steve 玩家皮肤。
 * <p>
 * 添加 {@link ItemInHandLayer} 以渲染主手/副手物品；
 * 添加 {@link HumanoidArmorLayer} 以渲染装备的盔甲；
 * 重写 {@link #extractRenderState} 调用 {@link HumanoidMobRenderer#extractHumanoidRenderState}
 * 填充手持物品、游泳/滑翔等数据；并手动设置手臂姿态（拉弓/持弩），
 * 因为 {@link LivingEntityRenderer} 本身不会设置手臂姿态。
 */
public class AICompanionRenderer extends LivingEntityRenderer<AICompanionEntity, AvatarRenderState, PlayerModel> {

    private static final Identifier STEVE_SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public AICompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        // 添加手持物品渲染层 — 使主手/副手物品可见
        this.addLayer(new ItemInHandLayer<>(this));
        // 添加盔甲渲染层 — 使装备的盔甲可见
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                ArmorModelSet.bake(
                        ModelLayers.PLAYER_ARMOR,
                        context.getModelSet(),
                        part -> new PlayerModel(part, false)),
                context.getEquipmentRenderer()));
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return STEVE_SKIN;
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    /**
     * 提取渲染状态 — 填充手持物品、手臂姿态、游泳/滑翔等数据。
     * <p>
     * 由于 {@link LivingEntityRenderer} 不会设置手臂姿态（只有 {@link HumanoidMobRenderer}
     * 的 {@code getArmPose()} 才会），在此手动检测拉弓/持弩并设置 {@code BOW_AND_ARROW}
     * / {@code CROSSBOW_CHARGE} / {@code CROSSBOW_HOLD} 姿态，否则手臂会保持默认姿势，
     * 表现为弓蓄力但不抬起。
     */
    @Override
    public void extractRenderState(AICompanionEntity entity, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(
                entity, state, partialTicks, this.itemModelResolver);

        // ── 手动设置手臂姿态（LivingEntityRenderer 不会自动处理）────────────
        if (entity.isUsingItem()) {
            ItemStack usingItem = entity.getUseItem();
            InteractionHand usingHand = entity.getUsedItemHand();
            boolean isRightHand = (usingHand == InteractionHand.MAIN_HAND);

            if (usingItem.getItem() instanceof BowItem) {
                // 拉弓姿态：双臂举起弓
                if (isRightHand) {
                    state.rightArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
                } else {
                    state.leftArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
                }
            } else if (usingItem.getItem() instanceof CrossbowItem) {
                // 弩装填/持握姿态
                HumanoidModel.ArmPose crossbowPose = CrossbowItem.isCharged(usingItem)
                        ? HumanoidModel.ArmPose.CROSSBOW_HOLD
                        : HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                if (isRightHand) {
                    state.rightArmPose = crossbowPose;
                } else {
                    state.leftArmPose = crossbowPose;
                }
            }
        }
    }
}
