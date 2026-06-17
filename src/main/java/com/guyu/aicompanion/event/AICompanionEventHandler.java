package com.guyu.aicompanion.event;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.guyu.aicompanion.entity.CompanionSpawner;
import com.guyu.aicompanion.entity.CompanionTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 处理将 AI 同伴与原版游戏机制集成的事件。
 * <p>
 * 功能：
 * <ul>
 *   <li>让所有敌对生物（Enemy）将 AI 同伴视为攻击目标</li>
 *   <li>玩家首次进入世界时自动生成同伴</li>
 *   <li>驱动同伴重生倒计时</li>
 * </ul>
 */
public class AICompanionEventHandler {

    /** 记录已经注入了同伴目标 AI 的生物，避免重复添加 */
    private static final Set<UUID> GOAL_INJECTED = new HashSet<>();

    /** 记录本 session 中已经自动生成过同伴的玩家，避免重复生成 */
    private static final Set<UUID> AUTO_SPAWNED = new HashSet<>();

    // ── 敌对生物 AI 注入 ─────────────────────────────────────────────────────

    /**
     * 当任意实体在服务器端加入世界时，检查它是否为敌对生物。
     * 如果是，则注入一个以 AI 同伴为目标的 {@link NearestAttackableTargetGoal}。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) return;
        if (!(mob instanceof Enemy)) return;

        // 避免在生物从磁盘重新加载时重复添加 goal
        if (GOAL_INJECTED.contains(mob.getUUID())) return;

        mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                mob, AICompanionEntity.class, 10, true, false, null));
        GOAL_INJECTED.add(mob.getUUID());
    }

    /**
     * 当生物被移除（消失/死亡/切换维度）时清理跟踪数据。
     */
    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        GOAL_INJECTED.remove(event.getEntity().getUUID());
    }

    // ── 玩家首次加入 → 自动生成同伴 ──────────────────────────────────────────

    /**
     * 玩家加入服务器时：
     * <ul>
     *   <li>如果世界中没有同伴且不在重生中 → 自动生成一个</li>
     *   <li>如果同伴正在重生且倒计时已结束 → 立即重生</li>
     * </ul>
     */
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        if (server == null) return;

        CompanionTracker tracker = CompanionTracker.get();

        // 如果追踪器没有 owner，尝试从世界中已有的同伴恢复
        if (tracker.getOwnerUuid() == null) {
            AICompanionEntity existing = findCompanionInServer(server);
            if (existing != null) {
                // 恢复 owner（如果同伴还没有 owner，设为当前玩家）
                if (existing.getOwnerUuid() == null) {
                    existing.setOwnerUuid(player.getUUID());
                }
                tracker.setOwnerUuid(existing.getOwnerUuid());
                AICompanion.LOGGER.info("[Companion] 从已有同伴恢复 owner: {}",
                        existing.getOwnerUuid());
                return;
            }
        }

        // 如果同伴正在重生且倒计时已结束但还没重生 → 立即重生
        if (tracker.isRespawning()) {
            tracker.tryRespawnIfReady(server);
            return;
        }

        // 如果 tracker 已有 owner 且世界中已有同伴 → 什么都不做
        if (tracker.getOwnerUuid() != null) {
            AICompanionEntity existing = findCompanionInServer(server);
            if (existing != null) return;
            // tracker 有 owner 但没有同伴实体 → 可能同伴在另一个维度或刚被移除
            // 此时不自动生成，等 companion 的实体在实体列表中重新出现或玩家用命令
        }

        // 没有同伴、没有重生中、没有 owner → 自动生成
        if (tracker.getOwnerUuid() == null && !tracker.isRespawning()) {
            AICompanionEntity companion = CompanionSpawner.spawnNearPlayer(player);
            if (companion != null) {
                tracker.setOwnerUuid(player.getUUID());
                AUTO_SPAWNED.add(player.getUUID());
                AICompanion.LOGGER.info("[Companion] 玩家 {} 首次加入，自动生成同伴",
                        player.getName().getString());
            }
        }
    }

    // ── 服务器 tick → 驱动重生倒计时 ─────────────────────────────────────────

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        CompanionTracker.get().tick(event.getServer());
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    /** 在整个服务器所有维度中搜索同伴实体 */
    private static AICompanionEntity findCompanionInServer(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof AICompanionEntity companion && !entity.isRemoved()) {
                    return companion;
                }
            }
        }
        return null;
    }
}
