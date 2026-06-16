package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.action.ActionExecutor;
import com.guyu.aicompanion.ai.AITickHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * AI Companion entity — visually identical to a Minecraft player (Steve skin).
 * Behavior is driven by an external AI system via {@link ActionExecutor}
 * combined with vanilla AI goals for auto-combat and ambient behavior.
 */
public class AICompanionEntity extends PathfinderMob {

    private final ActionExecutor actionExecutor;
    private final AITickHandler aiTickHandler;

    /**
     * Inventory — 27 slots like a player's main inventory.
     * Used to store mined blocks, tools, food, etc.
     */
    private final SimpleContainer inventory = new SimpleContainer(27);

    /**
     * Custom sleeping state.  We bypass vanilla's startSleeping/stopSleeping
     * because those require a bed block (checkBedExists() would fail every tick
     * and immediately call stopSleeping()).  Instead we track sleep ourselves
     * and force the SLEEPING pose each tick.
     */
    private boolean currentlySleeping = false;

    /** Hunger level (0-20, like a player). Decreases over time; at 0 the companion takes damage. */
    private int hunger = 20;
    private static final int MAX_HUNGER = 20;

    /** Counter for hunger tick — hunger decreases every 600 ticks (30 seconds). */
    private int hungerTickCounter = 0;
    private static final int HUNGER_TICK_INTERVAL = 600;

    public AICompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.aiTickHandler = new AITickHandler(this);
        this.actionExecutor = new ActionExecutor(this, aiTickHandler.getChatHistory());
    }

    @Override
    protected void registerGoals() {
        // ── Target goals (combat awareness) ─────────────────────────────────
        // Retaliate against anyone who hits us (priority 1 = highest)
        this.goalSelector.addGoal(1, new HurtByTargetGoal(this));
        // Auto-detect and target hostile mobs within 16 blocks
        // Note: null predicate means it will target any Mob; filtering to enemies only
        // would require predicate type workaround, acceptable for now
        this.targetSelector.addGoal(2,
                new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false, null));

        // ── Movement / behavior goals ───────────────────────────────────────
        // Float in water (prevents drowning)
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Melee attack when a target is locked on
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, false));
        // Look at nearby players
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        // Look around randomly (feels natural)
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        // Wander around when idle (makes the companion feel alive)
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6));
    }

    @Override
    public void tick() {
        super.tick();
        // Drive the action state machine on the server side.
        actionExecutor.tick();
        // Drive the AI decision loop (calls AI API every few seconds).
        aiTickHandler.tick();

        // Hunger tick (server only)
        if (!level().isClientSide()) {
            hungerTickCounter++;
            if (hungerTickCounter >= HUNGER_TICK_INTERVAL) {
                hungerTickCounter = 0;
                if (hunger > 0) {
                    hunger--;
                } else {
                    // Starving — take damage like a player on hard difficulty
                    if (getHealth() > 1.0F) {
                        hurt(damageSources().starve(), 1.0F);
                    }
                }
            }
        }

        // Force the SLEEPING pose every tick while sleeping.
        // This bypasses vanilla's bed-existence check that would otherwise
        // immediately wake the entity.
        if (currentlySleeping && !level().isClientSide()) {
            setPose(Pose.SLEEPING);
        }

        // Auto-pickup nearby items (server only)
        if (!level().isClientSide()) {
            tickPickupItems();
        }
    }

    /**
     * Automatically pick up nearby ItemEntitys into the inventory.
     * Checks within a 2-block radius each tick.
     */
    private void tickPickupItems() {
        net.minecraft.world.phys.AABB pickupBox = getBoundingBox().inflate(2.0);
        List<ItemEntity> nearbyItems = level().getEntitiesOfClass(ItemEntity.class, pickupBox);
        for (ItemEntity itemEntity : nearbyItems) {
            if (itemEntity.isRemoved()) continue;
            // Respect pickup delay (same as vanilla)
            if (itemEntity.hasPickUpDelay()) continue;

            ItemStack stack = itemEntity.getItem();
            ItemStack remainder = addToInventory(stack);
            if (remainder.isEmpty()) {
                itemEntity.discard();
            } else if (remainder.getCount() < stack.getCount()) {
                itemEntity.setItem(remainder);
            }
        }
    }

    /**
     * Try to add an ItemStack to the inventory.
     * Returns any items that didn't fit.
     */
    private ItemStack addToInventory(ItemStack stack) {
        // First try to stack with existing items
        int remaining = stack.getCount();
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = inventory.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int maxAdd = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                if (maxAdd > 0) {
                    slot.grow(maxAdd);
                    inventory.setItem(i, slot);
                    remaining -= maxAdd;
                }
            }
        }
        // Then try empty slots
        if (remaining > 0) {
            for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
                ItemStack slot = inventory.getItem(i);
                if (slot.isEmpty()) {
                    ItemStack toAdd = stack.copy();
                    toAdd.setCount(Math.min(remaining, stack.getMaxStackSize()));
                    inventory.setItem(i, toAdd);
                    remaining -= toAdd.getCount();
                }
            }
        }
        if (remaining <= 0) return ItemStack.EMPTY;
        ItemStack result = stack.copy();
        result.setCount(remaining);
        return result;
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

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        // Save inventory as a list using codec
        var invList = output.childrenList("Inventory");
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                var child = invList.addChild();
                child.putByte("Slot", (byte) i);
                child.store("Item", ItemStack.CODEC, stack);
            }
        }
        // Save sleep state
        output.putBoolean("CurrentlySleeping", currentlySleeping);
        // Save hunger
        output.putInt("Hunger", hunger);
        output.putInt("HungerTickCounter", hungerTickCounter);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        // Load inventory
        input.childrenList("Inventory").ifPresent(list -> {
            for (ValueInput child : list) {
                int slot = child.getByteOr("Slot", (byte) 0) & 0xFF;
                child.read("Item", ItemStack.CODEC).ifPresent(stack -> {
                    if (slot < inventory.getContainerSize()) {
                        inventory.setItem(slot, stack);
                    }
                });
            }
        });
        // Load sleep state
        currentlySleeping = input.getBooleanOr("CurrentlySleeping", false);
        // Load hunger
        hunger = input.getIntOr("Hunger", MAX_HUNGER);
        hungerTickCounter = input.getIntOr("HungerTickCounter", 0);
    }

    /**
     * Get the companion's inventory (27 slots, like a player's main inventory).
     */
    public SimpleContainer getInventory() {
        return inventory;
    }

    /** Get current hunger level (0-20). */
    public int getHunger() {
        return hunger;
    }

    /** Get max hunger level. */
    public int getMaxHunger() {
        return MAX_HUNGER;
    }

    /** Set hunger level, clamped to [0, MAX_HUNGER]. */
    public void setHunger(int value) {
        this.hunger = Math.max(0, Math.min(value, MAX_HUNGER));
    }

    /**
     * Try to eat food from inventory.
     * Returns true if food was consumed.
     */
    public boolean tryEat() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                FoodProperties food = stack.get(DataComponents.FOOD);
                if (food != null) {
                    int nutrition = food.nutrition();
                    setHunger(hunger + nutrition);
                    stack.shrink(1);
                    return true;
                }
            }
        }
        return false;
    }

    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    public AITickHandler getAiTickHandler() {
        return aiTickHandler;
    }
}
