package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.AICompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * 同伴生成工具 — 负责在玩家附近寻找安全的生成位置并生成同伴实体。
 */
public class CompanionSpawner {

    private CompanionSpawner() {}

    /**
     * 在玩家附近生成一个同伴实体。
     * 自动设置拥有者为该玩家。
     *
     * @return 生成的同伴，或生成失败时返回 null
     */
    @Nullable
    public static AICompanionEntity spawnNearPlayer(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos spawnPos = findSafeSpawnPos(level, player.blockPosition());

        AICompanionEntity entity = AICompanion.COMPANION.get().spawn(
                level, spawnPos, EntitySpawnReason.COMMAND);

        if (entity != null) {
            entity.setOwnerUuid(player.getUUID());
        } else {
            AICompanion.LOGGER.warn("[Companion] 在 {} 附近生成同伴失败",
                    spawnPos.toShortString());
        }
        return entity;
    }

    /**
     * 在指定位置附近寻找安全的生成位置。
     * 安全条件：脚下方块为实体方块，生成位置及上方一格为空气。
     * <p>
     * 搜索范围：以 center 为中心，螺旋向外搜索，半径最大 10 格。
     */
    private static BlockPos findSafeSpawnPos(ServerLevel level, BlockPos center) {
        // 先尝试玩家正上方 1 格（适合玩家在室内时）
        BlockPos above = center.above();
        if (isSafePos(level, above)) return above;

        // 螺旋搜索
        for (int r = 1; r <= 10; r++) {
            for (int angle = 0; angle < 360; angle += 45) {
                double rad = Math.toRadians(angle);
                int dx = (int) Math.round(r * Math.cos(rad));
                int dz = (int) Math.round(r * Math.sin(rad));

                // 尝试多个高度
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (isSafePos(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // 兜底：玩家位置上方两格
        return center.above(2);
    }

    /**
     * 判断指定位置是否安全（脚下为实体，位置本身和上方为空气）。
     */
    private static boolean isSafePos(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());

        return below.isSolid()
                && (at.isAir() || !at.blocksMotion())
                && (above.isAir() || !above.blocksMotion());
    }
}
