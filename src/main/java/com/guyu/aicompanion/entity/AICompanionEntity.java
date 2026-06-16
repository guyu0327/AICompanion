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
 * AI 同伴实体 — 外观与 Minecraft 玩家完全相同（Steve 皮肤）。
 * 行为由外部 AI 系统通过 {@link ActionExecutor} 驱动，
 * 结合原版 AI 目标实现自动战斗和环境行为。
 */
public class AICompanionEntity extends PathfinderMob {

    private final ActionExecutor actionExecutor;
    private final AITickHandler aiTickHandler;

    /**
     * 背包 — 27 格，类似玩家的主背包。
     * 用于存放挖掘的方块、工具、食物等。
     */
    private final SimpleContainer inventory = new SimpleContainer(27);

    /**
     * 自定义睡眠状态。我们绕过原版的 startSleeping/stopSleeping，
     * 因为它们需要床方块（checkBedExists() 每个 tick 都会失败并
     * 立即调用 stopSleeping()）。改为自行追踪睡眠状态，
     * 每 tick 强制设置为 SLEEPING 姿态。
     */
    private boolean currentlySleeping = false;

    /** 饥饿等级（0-20，类似玩家）。随时间减少；降到 0 时同伴会受伤。 */
    private int hunger = 20;
    private static final int MAX_HUNGER = 20;

    /** 饥饿计时器 — 每 600 ticks（30 秒）减少 1 点饥饿值 */
    private int hungerTickCounter = 0;
    private static final int HUNGER_TICK_INTERVAL = 600;

    public AICompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.aiTickHandler = new AITickHandler(this);
        this.actionExecutor = new ActionExecutor(this, aiTickHandler.getChatHistory());
    }

    @Override
    protected void registerGoals() {
        // ── 目标选择（战斗感知）─────────────────────────────────────────────
        // 反击任何攻击我们的实体（优先级 1 = 最高）
        this.goalSelector.addGoal(1, new HurtByTargetGoal(this));
        // 自动检测并锁定 16 格内的敌对生物
        // 注意：null 谓词表示会锁定任何 Mob；只针对敌对生物需要谓词类型变通，暂可接受
        this.targetSelector.addGoal(2,
                new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false, null));

        // ── 移动 / 行为目标 ─────────────────────────────────────────────────
        // 在水中漂浮（防止溺水）
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 锁定目标后进行近战攻击
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, false));
        // 看向附近的玩家
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        // 随机环顾四周（显得自然）
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        // 空闲时四处走动（让同伴显得有生命力）
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6));
    }

    @Override
    public void tick() {
        super.tick();
        // 在服务器端驱动动作状态机
        actionExecutor.tick();
        // 驱动 AI 决策循环（每隔几秒调用 AI API）
        aiTickHandler.tick();

        // 饥饿计时（仅服务器端）
        if (!level().isClientSide()) {
            hungerTickCounter++;
            if (hungerTickCounter >= HUNGER_TICK_INTERVAL) {
                hungerTickCounter = 0;
                if (hunger > 0) {
                    hunger--;
                } else {
                    // 饥饿 — 像玩家在困难难度下一样受到伤害
                    if (getHealth() > 1.0F) {
                        hurt(damageSources().starve(), 1.0F);
                    }
                }
            }
        }

        // 睡觉时每 tick 强制 SLEEPING 姿态。
        // 这绕过原版的床存在检查，否则会立即唤醒实体。
        if (currentlySleeping && !level().isClientSide()) {
            setPose(Pose.SLEEPING);
        }

        // 自动拾取附近的物品（仅服务器端）
        if (!level().isClientSide()) {
            tickPickupItems();
        }
    }

    /**
     * 自动将附近的 ItemEntity 拾取到背包中。
     * 每 tick 检查 2 格半径内。
     */
    private void tickPickupItems() {
        net.minecraft.world.phys.AABB pickupBox = getBoundingBox().inflate(2.0);
        List<ItemEntity> nearbyItems = level().getEntitiesOfClass(ItemEntity.class, pickupBox);
        for (ItemEntity itemEntity : nearbyItems) {
            if (itemEntity.isRemoved()) continue;
            // 尊重拾取延迟（与原版一致）
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
     * 尝试将 ItemStack 添加到背包中。
     * 返回未能放入的物品。
     */
    private ItemStack addToInventory(ItemStack stack) {
        // 先尝试与已有物品堆叠
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
        // 然后尝试空格子
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

    /** 进入自定义睡眠状态。每 tick 强制 SLEEPING 姿态 */
    public void setCompanionSleeping() {
        currentlySleeping = true;
        if (!level().isClientSide()) {
            setPose(Pose.SLEEPING);
        }
    }

    /** 离开自定义睡眠状态并重置为 STANDING */
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
        // 使用 codec 将背包保存为列表
        var invList = output.childrenList("Inventory");
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                var child = invList.addChild();
                child.putByte("Slot", (byte) i);
                child.store("Item", ItemStack.CODEC, stack);
            }
        }
        // 保存睡眠状态
        output.putBoolean("CurrentlySleeping", currentlySleeping);
        // 保存饥饿值
        output.putInt("Hunger", hunger);
        output.putInt("HungerTickCounter", hungerTickCounter);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        // 加载背包
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
        // 加载睡眠状态
        currentlySleeping = input.getBooleanOr("CurrentlySleeping", false);
        // 加载饥饿值
        hunger = input.getIntOr("Hunger", MAX_HUNGER);
        hungerTickCounter = input.getIntOr("HungerTickCounter", 0);
    }

    /**
     * 获取同伴的背包（27 格，类似玩家的主背包）
     */
    public SimpleContainer getInventory() {
        return inventory;
    }

    /** 获取当前饥饿等级（0-20） */
    public int getHunger() {
        return hunger;
    }

    /** 获取最大饥饿等级 */
    public int getMaxHunger() {
        return MAX_HUNGER;
    }

    /** 设置饥饿等级，限制在 [0, MAX_HUNGER] 范围内 */
    public void setHunger(int value) {
        this.hunger = Math.max(0, Math.min(value, MAX_HUNGER));
    }

    /**
     * 尝试从背包中吃食物。
     * 返回是否成功消耗了食物。
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
