package com.guyu.aicompanion.event;

import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 处理将 AI 同伴与原版游戏机制集成的事件。
 * <p>
 * 目前的作用：让所有敌对生物（Enemy）将 AI 同伴视为攻击目标。
 * 如果没有此处理，僵尸、骷髅等只会攻击玩家而忽略同伴。
 */
public class AICompanionEventHandler {

    /** 记录已经注入了同伴目标 AI 的生物，避免重复添加 */
    private static final Set<UUID> GOAL_INJECTED = new HashSet<>();

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
    public void onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        GOAL_INJECTED.remove(event.getEntity().getUUID());
    }
}
