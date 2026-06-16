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
 * Handles events to integrate the AI Companion with vanilla game mechanics.
 * <p>
 * Currently: makes all hostile mobs (Enemy) aware of the AI Companion as a target.
 * Without this, zombies, skeletons, etc. only target players and ignore the companion.
 */
public class AICompanionEventHandler {

    /** Tracks mobs that already have the companion-targeting goal injected. */
    private static final Set<UUID> GOAL_INJECTED = new HashSet<>();

    /**
     * When any entity joins a level on the server, check if it's a hostile mob.
     * If so, inject a {@link NearestAttackableTargetGoal} that targets AI Companions.
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) return;
        if (!(mob instanceof Enemy)) return;

        // Avoid adding duplicate goals when the mob is re-loaded from disk
        if (GOAL_INJECTED.contains(mob.getUUID())) return;

        mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                mob, AICompanionEntity.class, 10, true, false, null));
        GOAL_INJECTED.add(mob.getUUID());
    }

    /**
     * Clean up tracking data when a mob is removed (despawn / death / dimension change).
     */
    @SubscribeEvent
    public void onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        GOAL_INJECTED.remove(event.getEntity().getUUID());
    }
}
