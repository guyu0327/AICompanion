package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 即时动作控制器 — 处理所有在一两个 tick 内完成的动作：
 * 聊天、进食、睡觉、醒来、丢弃物品、使用物品、放置方块。
 * <p>
 * 这些动作没有持续的 tick 循环 — 执行后直接调用
 * {@code executor.completeAction()} 回到 IDLE 状态。
 */
class ItemController extends ActionController {

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
        if (companion.getHunger() >= companion.getMaxHunger()) {
            executor.announce("我不饿，不需要吃东西");
            executor.completeAction();
            return;
        }
        // 尝试从背包中找食物
        if (companion.tryEat()) {
            companion.swing(InteractionHand.MAIN_HAND);
            executor.announce("吃了点东西，饱食度恢复到 " + companion.getHunger());
        } else {
            executor.announce("背包里没有食物可以吃");
        }
        executor.completeAction();
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
