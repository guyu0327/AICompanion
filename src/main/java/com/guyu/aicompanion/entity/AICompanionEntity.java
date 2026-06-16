package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.action.ActionExecutor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * AI Companion entity — visually identical to a Minecraft player (Steve skin).
 * Behavior is driven by an external AI system via {@link ActionExecutor}.
 */
public class AICompanionEntity extends Mob {

    private final ActionExecutor actionExecutor;

    public AICompanionEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        this.actionExecutor = new ActionExecutor(this);
    }

    @Override
    protected void registerGoals() {
        // No vanilla AI goals — behavior is driven by the AI brain + ActionExecutor
    }

    @Override
    public void tick() {
        super.tick();
        // Drive the action state machine on the server side.
        actionExecutor.tick();
    }

    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }
}
