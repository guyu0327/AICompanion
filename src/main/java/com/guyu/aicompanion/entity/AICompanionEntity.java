package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.action.ActionExecutor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

/**
 * AI Companion entity — visually identical to a Minecraft player (Steve skin).
 * Behavior is driven by an external AI system via {@link ActionExecutor}.
 */
public class AICompanionEntity extends Mob {

    private final ActionExecutor actionExecutor;

    /**
     * Custom sleeping state.  We bypass vanilla's startSleeping/stopSleeping
     * because those require a bed block (checkBedExists() would fail every tick
     * and immediately call stopSleeping()).  Instead we track sleep ourselves
     * and force the SLEEPING pose each tick.
     */
    private boolean currentlySleeping = false;

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

        // Force the SLEEPING pose every tick while sleeping.
        // This bypasses vanilla's bed-existence check that would otherwise
        // immediately wake the entity.
        if (currentlySleeping && !level().isClientSide()) {
            setPose(Pose.SLEEPING);
        }
    }

    /** Enter the custom sleep state.  Pose is forced to SLEEPING each tick. */
    public void setCompanionSleeping() {
        currentlySleeping = true;
        if (!level().isClientSide()) {
            setPose(Pose.SLEEPING);
        }
    }

    /** Leave the custom sleep state and reset to STANDING. */
    public void wakeCompanionUp() {
        currentlySleeping = false;
        if (!level().isClientSide()) {
            setPose(Pose.STANDING);
        }
    }

    public boolean isCompanionSleeping() {
        return currentlySleeping;
    }

    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }
}
