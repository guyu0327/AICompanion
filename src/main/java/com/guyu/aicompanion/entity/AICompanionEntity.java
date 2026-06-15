package com.guyu.aicompanion.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * AI Companion entity — visually identical to a Minecraft player (Steve skin).
 * Currently a basic entity; AI behavior will be added in later phases.
 */
public class AICompanionEntity extends Mob {

    public AICompanionEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        // No vanilla AI goals — behavior will be driven by the external AI system
    }
}
