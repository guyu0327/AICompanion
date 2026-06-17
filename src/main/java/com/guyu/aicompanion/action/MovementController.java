package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

/**
 * 移动动作控制器 — 处理 {@code MOVE} 动作的路径规划和到达检测。
 */
class MovementController extends ActionController {

    private static final double MOVE_SPEED = 1.0;
    private static final double ARRIVAL_THRESHOLD = 2.0; // 方块 — 移动到此范围内视为到达

    private BlockPos moveTarget;

    MovementController(AICompanionEntity companion, ChatHistory chatHistory, ActionExecutor executor) {
        super(companion, chatHistory, executor);
    }

    void beginMove(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] MOVE action missing target position");
            executor.completeAction();
            return;
        }
        moveTarget = action.getTargetPos();
        PathNavigation nav = companion.getNavigation();
        boolean started = nav.moveTo(
                moveTarget.getX() + 0.5,
                moveTarget.getY(),
                moveTarget.getZ() + 0.5,
                MOVE_SPEED
        );
        if (started) {
            executor.setState(ActionExecutor.State.MOVING);
            executor.announce("正在前往 " + moveTarget.toShortString());
        } else {
            AICompanion.LOGGER.warn("[AI] 无法规划到 {} 的路径", moveTarget.toShortString());
            executor.announce("找不到去那里的路");
            executor.completeAction();
        }
    }

    void tickMove() {
        if (moveTarget == null) {
            executor.completeAction();
            return;
        }

        // 路径丢失或卡住
        if (companion.getNavigation().isDone()) {
            double dist = companion.position().distanceTo(Vec3.atCenterOf(moveTarget));
            if (dist <= ARRIVAL_THRESHOLD) {
                executor.announce("已到达 " + moveTarget.toShortString());
                executor.completeAction();
            } else {
                // 尝试重新寻路
                boolean repath = companion.getNavigation().moveTo(
                        moveTarget.getX() + 0.5, moveTarget.getY(),
                        moveTarget.getZ() + 0.5, MOVE_SPEED);
                if (!repath) {
                    executor.announce("卡住了，无法到达目的地");
                    executor.completeAction();
                }
            }
        }
    }

    /** 停止导航（在取消动作时由 ActionExecutor 调用） */
    void cleanup() {
        companion.getNavigation().stop();
    }
}
