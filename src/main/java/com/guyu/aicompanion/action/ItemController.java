package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 即时动作控制器 — 处理所有在一两个 tick 内完成的动作：
 * 聊天、进食、睡觉、醒来、丢弃物品、使用物品、放置方块。
 * <p>
 * 进食动作是例外 — 它有持续的 tick 循环（eating 动画和音效）。
 */
class ItemController extends ActionController {

    /** 进食所需 tick 数（与玩家一致：32 ticks = 1.6 秒） */
    private static final int EAT_DURATION = 32;
    /** 进食音效间隔（每 4-5 tick 播放一次咀嚼声） */
    private static final int EAT_SOUND_INTERVAL = 5;

    // 进食状态
    private int eatTicksRemaining = 0;
    private ItemStack eatingItem = ItemStack.EMPTY;

    ItemController(AICompanionEntity companion, ChatHistory chatHistory, ActionExecutor executor) {
        super(companion, chatHistory, executor);
    }

    void executeChat(Action action) {
        String msg = action.getMessage();
        if (msg != null && !msg.isBlank()) {
            executor.broadcast(msg);
        }
        executor.completeAction();
    }

    void executeEat() {
        // 防止重复进食：如果已经在进食中，直接跳过
        if (eatTicksRemaining > 0) {
            executor.completeAction();
            return;
        }

        if (companion.getHunger() >= companion.getMaxHunger()) {
            executor.announce("我不饿，不需要吃东西");
            executor.completeAction();
            return;
        }

        // 从背包中找食物并移到主手
        int foodSlot = findFoodInInventory();
        if (foodSlot < 0) {
            executor.announce("背包里没有食物可以吃");
            executor.completeAction();
            return;
        }

        // 将食物移到主手
        ItemStack food = companion.getInventory().getItem(foodSlot);
        eatingItem = food.copy();

        // 保存主手原有物品
        ItemStack oldHand = companion.getMainHandItem().copy();

        // 装备食物到主手
        companion.setItemInHand(InteractionHand.MAIN_HAND, food.copy());
        companion.getInventory().setItem(foodSlot, ItemStack.EMPTY);

        // 将原来的手持物品放回背包
        if (!oldHand.isEmpty()) {
            companion.addToInventory(oldHand);
        }

        // 开始进食（触发进食动画）
        companion.startUsingItem(InteractionHand.MAIN_HAND);
        eatTicksRemaining = EAT_DURATION;
        executor.setState(ActionExecutor.State.EATING);

        executor.announce("开始吃 " + eatingItem.getHoverName().getString());
    }

    /**
     * 每 tick 驱动进食动作。播放音效，完成后消耗食物并恢复饥饿值。
     */
    void tickEat() {
        if (eatTicksRemaining <= 0) {
            finishEating();
            return;
        }

        eatTicksRemaining--;

        // 播放进食音效（每 5 tick 一次，类似玩家）
        if (eatTicksRemaining % EAT_SOUND_INTERVAL == 0) {
            ServerLevel level = (ServerLevel) companion.level();
            level.playSound(null, companion.getX(), companion.getY(), companion.getZ(),
                    SoundEvents.GENERIC_EAT,
                    SoundSource.NEUTRAL,
                    0.5F + 0.5F * level.getRandom().nextFloat(),
                    level.getRandom().nextFloat() * 0.1F + 0.9F);
        }

        // 进食完成
        if (eatTicksRemaining <= 0) {
            finishEating();
        }
    }

    /**
     * 完成进食：消耗食物、恢复饥饿值、停止动画。
     */
    private void finishEating() {
        // 停止使用物品（停止进食动画）
        companion.stopUsingItem();

        // 获取食物的营养值
        if (!eatingItem.isEmpty()) {
            FoodProperties food = eatingItem.get(DataComponents.FOOD);
            if (food != null) {
                int nutrition = food.nutrition();
                // 饱和度恢复（与玩家 0.6 ratio 等效）
                float saturationRestore = nutrition * 0.6F * 2.0F;
                companion.setHunger(companion.getHunger() + nutrition);
                companion.setSaturation(companion.getSaturation() + saturationRestore);
            }

            // 消耗主手中的食物
            ItemStack held = companion.getMainHandItem();
            held.shrink(1);

            // 如果食物有剩余物品（如碗），放回背包
            if (!held.isEmpty()) {
                companion.addToInventory(held.copy());
                held.setCount(0);
            }

            executor.announce("吃完了，饱食度恢复到 " + companion.getHunger());
        }

        // 重置状态
        eatingItem = ItemStack.EMPTY;
        executor.completeAction();
    }

    /**
     * 在背包中查找食物，返回格子索引。没有找到返回 -1。
     */
    private int findFoodInInventory() {
        for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
            ItemStack stack = companion.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 取消进食动作时的清理。
     */
    void cleanupEat() {
        if (eatTicksRemaining > 0) {
            companion.stopUsingItem();
            eatTicksRemaining = 0;
            eatingItem = ItemStack.EMPTY;
        }
    }

    void executeSleep() {
        if (companion.isCompanionSleeping()) {
            executor.announce("已经在休息了 zzZ");
            executor.completeAction();
            return;
        }
        companion.setCompanionSleeping();
        executor.announce("休息一下 zzZ");
        executor.completeAction();
    }

    void executeWakeUp() {
        if (companion.isCompanionSleeping()) {
            companion.wakeCompanionUp();
            executor.announce("醒来了！");
        } else {
            companion.wakeCompanionUp(); // 同时重置姿态
            executor.announce("已经是清醒状态");
        }
        executor.completeAction();
    }

    void executeDropItem() {
        ItemStack stack = companion.getMainHandItem();
        if (stack.isEmpty()) {
            executor.announce("手里没东西可丢");
        } else {
            ServerLevel level = (ServerLevel) companion.level();
            companion.spawnAtLocation(level, stack.copy());
            stack.setCount(0);
            executor.announce("扔掉了手里的东西");
        }
        executor.completeAction();
    }

    void executeUseItem() {
        // 占位实现：只挥动胳膊。真正的物品使用逻辑取决于物品类型
        ItemStack stack = companion.getMainHandItem();
        if (stack.isEmpty()) {
            executor.announce("手里没有物品");
        } else {
            companion.swing(InteractionHand.MAIN_HAND);
            executor.announce("使用了 " + stack.getHoverName().getString());
        }
        executor.completeAction();
    }

    void executePlace(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] PLACE_BLOCK missing targetPos");
            executor.completeAction();
            return;
        }
        ServerLevel level = (ServerLevel) companion.level();
        BlockPos pos = action.getTargetPos();
        if (!level.getBlockState(pos).isAir()) {
            executor.announce("那个位置已经有方块了");
            executor.completeAction();
            return;
        }
        // 检查手中是否有方块，否则尝试从背包中找
        ItemStack held = companion.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) {
            // 尝试在背包中找到方块物品并装备
            for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
                ItemStack stack = companion.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                    // 装备到主手
                    ItemStack oldHand = companion.getMainHandItem().copy();
                    companion.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
                    stack.setCount(0);
                    companion.getInventory().setItem(i, ItemStack.EMPTY);
                    // 将原来的手持物品放回背包
                    if (!oldHand.isEmpty()) {
                        companion.addToInventory(oldHand);
                    }
                    held = companion.getMainHandItem();
                    break;
                }
            }
        }
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) {
            executor.announce("背包里没有方块可以放置");
            executor.completeAction();
            return;
        }
        BlockItem blockItem = (BlockItem) held.getItem();

        // 支撑检查：目标位置的相邻方块中至少有一个非空气（防止浮空放置）
        boolean hasSupport = false;
        for (Direction dir : Direction.values()) {
            if (!level.getBlockState(pos.relative(dir)).isAir()) {
                hasSupport = true;
                break;
            }
        }
        if (!hasSupport) {
            executor.announce("那个位置没有支撑，无法放置");
            executor.completeAction();
            return;
        }

        if (level.setBlock(pos, blockItem.getBlock().defaultBlockState(), 3)) {
            held.shrink(1);
            companion.swing(InteractionHand.MAIN_HAND);
            executor.announce("放置了方块");
        } else {
            executor.announce("放置失败");
        }
        executor.completeAction();
    }
}
