package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 挖掘动作控制器 — 处理 {@code MINE} 动作的完整生命周期：
 * 走到方块旁、自动装备工具、逐 tick 挖掘进度、裂纹动画、方块破碎。
 * <p>
 * 包含自我保护逻辑：脚下有危险方块时中断挖掘。
 */
class MiningController extends ActionController {

    private static final double MOVE_SPEED = 1.0;
    private static final double MINE_REACH = 2.5; // 必须在此范围内才能开始挖掘

    private BlockPos mineTarget;
    private int mineProgress;
    private int mineTotalTicks;

    MiningController(AICompanionEntity companion, ChatHistory chatHistory, ActionExecutor executor) {
        super(companion, chatHistory, executor);
    }

    void beginMine(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] MINE action missing target position");
            executor.completeAction();
            return;
        }
        mineTarget = action.getTargetPos();

        // 挖掘前自动从背包装备最佳工具
        autoEquipToolForBlock(mineTarget);

        mineProgress = 0;
        mineTotalTicks = calculateMineTicks(mineTarget);

        if (mineTotalTicks >= Integer.MAX_VALUE) {
            executor.announce("这个方块挖不动（基岩等）");
            executor.completeAction();
            return;
        }

        executor.setState(ActionExecutor.State.MINING);
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(mineTarget);
        String blockName = bs.getBlock().builtInRegistryHolder().key().identifier().getPath();
        boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
        executor.announce("开始挖掘 " + blockName + " " + mineTarget.toShortString()
                + " (预计 " + (mineTotalTicks / 20) + " 秒"
                + (correctTool ? "" : " — 没有合适的工具！") + ")");
    }

    void tickMine() {
        ServerLevel level = (ServerLevel) companion.level();

        // ── 自我保护：脚下有危险方块时中断挖掘 ────────────────────────────────
        if (isInDanger()) {
            resetBlockCrack(level);
            executor.announce("脚下有危险，快离开！");
            companion.getNavigation().moveTo(
                    companion.getX(), companion.getY() + 1,
                    companion.getZ(), MOVE_SPEED);
            executor.completeAction();
            return;
        }

        BlockState bs = level.getBlockState(mineTarget);

        // 方块消失了（被其他人挖掉，或者本来就是空气）
        if (bs.isAir()) {
            resetBlockCrack(level);
            executor.announce("方块已经被挖掉了");
            executor.completeAction();
            return;
        }

        // ── 第一步：走到方块旁边 ──────────────────────────────────────────────
        double dist = companion.position().distanceTo(Vec3.atCenterOf(mineTarget));
        if (dist > MINE_REACH) {
            resetBlockCrack(level);
            companion.getNavigation().moveTo(
                    mineTarget.getX() + 0.5, mineTarget.getY(),
                    mineTarget.getZ() + 0.5, MOVE_SPEED);
            return; // 等待靠近
        }

        // ── 第二步：在范围内 — 开始挖掘！──────────────────────────────────
        // 面向方块
        companion.getLookControl().setLookAt(
                mineTarget.getX() + 0.5,
                mineTarget.getY() + 0.5,
                mineTarget.getZ() + 0.5);

        // 手臂挥动动画（模拟玩家）
        companion.swing(InteractionHand.MAIN_HAND);

        // 播放方块的敲击音效（每次挥动的"挖掘"声）
        SoundType soundType = bs.getSoundType();
        level.playSound(null,
                mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5,
                soundType.getHitSound(),
                SoundSource.BLOCKS,
                soundType.getVolume() * 0.5F, // 挖掘时音量较小
                soundType.getPitch());

        mineProgress++;

        // 更新裂纹动画：进度从 0（无裂纹）到 9（即将破碎）
        int crackStage = (int) ((double) mineProgress / mineTotalTicks * 9);
        crackStage = Math.max(0, Math.min(9, crackStage));
        level.destroyBlockProgress(companion.getId(), mineTarget, crackStage);

        if (mineProgress >= mineTotalTicks) {
            resetBlockCrack(level);

            // 破坏方块 — 只有使用正确工具时才掉落物品
            // （匹配原版行为：空手挖石头不掉落任何东西）
            boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
            boolean shouldDrop = correctTool || !bs.requiresCorrectToolForDrops();

            if (shouldDrop) {
                level.destroyBlock(mineTarget, true, companion, 32);
            } else {
                // 移除方块但不掉落物品（像玩家空手挖石头一样）
                level.destroyBlock(mineTarget, false, companion, 32);
            }

            // 播放方块破碎音效
            level.playSound(null,
                    mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5,
                    soundType.getBreakSound(),
                    SoundSource.BLOCKS,
                    soundType.getVolume(), soundType.getPitch());

            executor.announce("挖完了！");
            executor.completeAction();
        }
    }

    /** 清除方块裂纹动画并重置进度（取消动作时由 ActionExecutor 调用） */
    void cleanup() {
        if (!companion.level().isClientSide()) {
            resetBlockCrack((ServerLevel) companion.level());
        }
    }

    // ── 工具相关辅助 ─────────────────────────────────────────────────────────

    /** 清除方块裂纹动画（将进度设为 -1） */
    private void resetBlockCrack(ServerLevel level) {
        if (mineTarget != null) {
            level.destroyBlockProgress(companion.getId(), mineTarget, -1);
        }
    }

    /**
     * 自动从背包装备挖掘指定方块的最佳工具。
     * 如果当前手持物品已经够用，则不做任何操作。
     */
    private void autoEquipToolForBlock(BlockPos pos) {
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(pos);

        // 瞬间可挖掘的方块无需换工具
        float hardness = bs.getDestroySpeed(level, pos);
        if (hardness <= 0) return;

        // 如果当前手持工具已经合适，跳过
        ItemStack held = companion.getMainHandItem();
        if (isCorrectToolForBlock(held, bs) && getToolSpeed(held, bs) >= 4.0F) return;

        // 在背包中搜索最佳工具（使用原版 API 判断正确工具 + 挖掘速度）
        int bestSlot = -1;
        float bestSpeed = getToolSpeed(held, bs);

        for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
            ItemStack stack = companion.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            // 只考虑能正确挖掘该方块的物品（镐、斧、铲、剑、锄等）
            if (isCorrectToolForBlock(stack, bs)) {
                float speed = getToolSpeed(stack, bs);
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0) {
            ItemStack tool = companion.getInventory().getItem(bestSlot);
            ItemStack oldHand = companion.getMainHandItem().copy();
            companion.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
            companion.getInventory().setItem(bestSlot, ItemStack.EMPTY);
            // 将原来手中的物品放回刚空出的格子
            if (!oldHand.isEmpty()) {
                companion.getInventory().setItem(bestSlot, oldHand);
            }
            String toolName = companion.getMainHandItem().getItem()
                    .builtInRegistryHolder().key().identifier().getPath();
            executor.announce("装备了 " + toolName);
        }
    }

    /**
     * 计算挖掘所需 tick 数 — 匹配原版 Minecraft 公式。
     * <p>
     * 原版公式：
     * <ul>
     *   <li>使用正确工具：damagePerTick = toolSpeed / hardness / 30</li>
     *   <li>不使用正确工具：damagePerTick = 1 / hardness / 100</li>
     *   <li>所需时间 = ceil(1 / damagePerTick)</li>
     * </ul>
     * 工具速度：空手=1，木/金=2，石=4，铁=6，钻石=8，下界合金=9
     */
    private int calculateMineTicks(BlockPos pos) {
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(pos);
        float hardness = bs.getDestroySpeed(level, pos);
        if (hardness < 0) return Integer.MAX_VALUE; // 不可破坏（基岩等）
        if (hardness == 0) return 5;                 // 瞬间可挖掘的方块（火把、花等）

        boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
        float ticks;
        if (correctTool) {
            float toolSpeed = getToolSpeed(companion.getMainHandItem(), bs);
            // 原版公式：时间 = hardness * 30 / toolSpeed
            ticks = hardness * 30.0F / toolSpeed;
        } else {
            // 原版空手挖掘不可收获方块：时间 = hardness * 100
            ticks = hardness * 100.0F;
        }
        return Math.max(5, (int) Math.ceil(ticks));
    }

    /**
     * 获取工具对指定方块的挖掘速度（委托原版 API）。
     * 空手或非工具物品由原版逻辑返回对应速度。
     */
    private float getToolSpeed(ItemStack held, BlockState bs) {
        return held.getDestroySpeed(bs);
    }

    /**
     * 检查手持物品是否是对应方块的"正确"工具（原版 API）。
     * 自动支持所有原版与 mod 方块。
     */
    private boolean isCorrectToolForBlock(ItemStack held, BlockState bs) {
        return held.isCorrectToolForDrops(bs);
    }

    // ── 自我保护：危险方块检测 ─────────────────────────────────────────────────

    /**
     * 检查同伴脚下是否有危险方块（岩浆、岩浆块、仙人掌、火等）。
     */
    private boolean isInDanger() {
        ServerLevel level = (ServerLevel) companion.level();
        BlockPos pos = companion.blockPosition();
        // 检查脚下及周围下方的方块
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos check = pos.offset(dx, -1, dz);
                Block block = level.getBlockState(check).getBlock();
                if (isDangerousBlock(block)) return true;
            }
        }
        // 检查当前位置（站立层）
        if (isDangerousBlock(level.getBlockState(pos).getBlock())) return true;
        return false;
    }

    private boolean isDangerousBlock(Block block) {
        return block == Blocks.LAVA
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_CAMPFIRE;
    }
}
