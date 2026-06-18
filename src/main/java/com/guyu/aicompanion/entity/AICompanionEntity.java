package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.Config;
import com.guyu.aicompanion.action.Action;
import com.guyu.aicompanion.action.ActionExecutor;
import com.guyu.aicompanion.action.ActionType;
import com.guyu.aicompanion.ai.AITickHandler;
import com.guyu.aicompanion.menu.CompanionInventoryMenu;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * AI 同伴实体 — 外观与 Minecraft 玩家完全相同（Steve 皮肤）。
 * 行为由外部 AI 系统通过 {@link ActionExecutor} 驱动，
 * 结合原版 AI 目标实现自动战斗和环境行为。
 * <p>
 * 实现 {@link MenuProvider} 以支持 Shift+右键打开背包 GUI。
 */
public class AICompanionEntity extends PathfinderMob implements MenuProvider {

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

    /** 饥饿等级（0-20，类似玩家）。随行动消耗；降到 0 时同伴会受伤。 */
    private int hunger = 20;
    private static final int MAX_HUNGER = 20;

    /**
     * 饱和度（0-20，隐藏属性）。吃东西时恢复，优先于饥饿值消耗。
     * 与玩家机制一致：饱和度 > 0 时先消耗饱和度，耗尽后才消耗饥饿值。
     */
    private float saturation = 5.0F;

    /**
     * 消耗度（0-4，隐藏属性）。行动（移动、战斗、跳跃）会增加消耗度，
     * 达到 4 时消耗 1 点饱和度或饥饿值。与玩家机制一致。
     */
    private float exhaustion = 0.0F;
    private static final float MAX_EXHAUSTION = 4.0F;

    /** 自然回血计时器 — 与玩家的 foodTickTimer 类似 */
    private int regenTickCounter = 0;

    /** 行为模式：跟随 / 待命 / 自由 */
    private CompanionMode mode = CompanionMode.FOLLOW;

    /** 拥有者的 UUID — 跟随模式下会追踪此玩家 */
    private @Nullable UUID ownerUuid = null;

    /** 右键交互的 tick 冷却 — 防止 MC 单次右键多次触发 mobInteract */
    private int lastInteractTick = -1;

    /** FOLLOW 模式下，距离拥有者超过此距离才会触发跟随移动 */
    private static final double FOLLOW_TRIGGER_DISTANCE = 4.0;
    /** 睡觉回血间隔 — 每 100 ticks（5 秒）回复 1 HP */
    private static final int SLEEP_HEAL_INTERVAL = 100;
    private int sleepHealCounter = 0;
    /** 盔甲评估间隔 — 每 10 ticks（0.5 秒）检查一次是否需要更换盔甲 */
    private static final int ARMOR_EVAL_INTERVAL = 10;
    private int armorEvalCounter = 0;

    /** 向玩家索要食物的冷却（防止刷屏）— 600 ticks = 30 秒 */
    private static final int ASK_FOOD_COOLDOWN = 600;
    private int askFoodCooldown = 0;

    /** 进食间隔冷却 — 防止连续快速吃东西（20 ticks = 1 秒） */
    private static final int EAT_COOLDOWN = 20;
    private int eatCooldown = 0;

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
        // 自动检测并锁定 16 格内的敌对生物（Enemy 接口：僵尸、骷髅、蜘蛛等）
        // 通过谓词过滤，避免攻击村民、玩家宠物、商队羊驼等友好生物
        this.targetSelector.addGoal(2,
                new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                        (target, level) -> target instanceof Enemy));

        // ── 移动 / 行为目标 ─────────────────────────────────────────────────
        // 在水中漂浮（防止溺水）
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 注意：不使用 MeleeAttackGoal — 战斗逻辑完全由 ActionExecutor.tickAttack() 统一处理
        // 这样可以根据装备（近战/远程武器）选择不同的攻击行为
        // 看向附近的玩家
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        // 随机环顾四周（显得自然）
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        // 空闲时四处走动（让同伴显得有生命力）
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6));
    }

    @Override
    public void tick() {
        // 有攻击目标时：自动装备最佳武器 + 自动触发攻击动作
        // 战斗逻辑完全由 ActionExecutor 统一处理（近战/远程都支持）
        // 所有模式下都允许反击（包括 STAND 模式）
        if (!level().isClientSide() && getTarget() != null) {
            actionExecutor.autoEquipWeapon(getTarget());
            // 仅在 IDLE 状态且不在撤退冷却中时启动攻击动作
            if (actionExecutor.getState() == ActionExecutor.State.IDLE
                    && !actionExecutor.isRetreating()) {
                String entityName = getTarget().getType()
                        .builtInRegistryHolder().key().identifier().getPath();
                actionExecutor.startAction(
                        Action.attack(entityName, 24, "自动锁定目标"));
            }
        }

        super.tick();
        // 在服务器端驱动动作状态机
        actionExecutor.tick();

        if (!level().isClientSide()) {
            // FOLLOW 模式：空闲时跟随拥有者
            if (mode == CompanionMode.FOLLOW && actionExecutor.isIdle()
                    && getTarget() == null && !currentlySleeping) {
                tickFollow();
            }

            // STAND 模式下跳过 AI 决策，FREE 模式下正常驱动 AI
            if (mode != CompanionMode.STAND) {
                aiTickHandler.tick();
            }

            // ── 饥饿/饱和度/消耗度系统（与玩家机制一致）────────────────────
            tickFoodAndRegen();

            // ── 自动进食逻辑（优先级高于 AI 决策）─────────────────────────────
            tickAutoEat();

            // 移动增加消耗度（与玩家一致：冲刺 0.1/tick，行走约 0.01/tick）
            if (isInWater()) {
                addExhaustion(0.0125F); // 游泳
            } else if (getDeltaMovement().horizontalDistanceSqr() > 0.01) {
                // 正在移动
                if (isSprinting()) {
                    addExhaustion(0.1F); // 冲刺
                } else {
                    addExhaustion(0.01F); // 行走
                }
            }

            // 盔甲自动装备（每 10 tick 评估一次，避免频繁遍历背包）
            armorEvalCounter++;
            if (armorEvalCounter >= ARMOR_EVAL_INTERVAL) {
                armorEvalCounter = 0;
                actionExecutor.autoEquipArmor();
            }

            // 睡觉时每 tick 强制 SLEEPING 姿态 + 回血
            if (currentlySleeping) {
                setPose(Pose.SLEEPING);
                sleepHealCounter++;
                if (sleepHealCounter >= SLEEP_HEAL_INTERVAL) {
                    sleepHealCounter = 0;
                    if (getHealth() < getMaxHealth()) {
                        heal(1.0F);
                    }
                }
            } else {
                sleepHealCounter = 0;
            }

            // 自动拾取附近的物品（所有模式都拾取）
            tickPickupItems();
        }
    }

    /**
     * FOLLOW 模式：跟随拥有者。
     * 当距离超过 {@value #FOLLOW_TRIGGER_DISTANCE} 格时，
     * 启动移动到拥有者位置的动作。
     */
    private void tickFollow() {
        ServerPlayer owner = getOwner();
        if (owner == null || owner.level() != level()) return;

        double dist = distanceTo(owner);
        if (dist > FOLLOW_TRIGGER_DISTANCE) {
            net.minecraft.core.BlockPos ownerPos = owner.blockPosition();
            getNavigation().moveTo(
                    ownerPos.getX() + 0.5, ownerPos.getY(),
                    ownerPos.getZ() + 0.5, 1.2);
        } else {
            // 距离足够近，停止移动
            getNavigation().stop();
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
     * 饥饿/饱和度/自然回血系统 — 与玩家机制一致。
     * <p>
     * 流程（每 tick 执行）：
     * <ol>
     *   <li>处理消耗度：达到 4.0 时消耗饱和度或饥饿值</li>
     *   <li>自然回血：饥饿 >= 18 且饱和度 > 0 时，消耗饱和度回血</li>
     *   <li>饥饿回血：饥饿 >= 18 且饱和度 = 0 时，缓慢回血（消耗饥饿值）</li>
     *   <li>饥饿伤害：饥饿 = 0 时受到伤害（困难模式：扣到 1 HP）</li>
     * </ol>
     */
    private void tickFoodAndRegen() {
        // ── 消耗度处理（exhaustion → saturation → hunger）─────────────────
        if (exhaustion > MAX_EXHAUSTION) {
            exhaustion -= MAX_EXHAUSTION;
            if (saturation > 0) {
                saturation = Math.max(saturation - 1.0F, 0.0F);
            } else if (hunger > 0) {
                hunger--;
            }
        }

        // ── 自然回血（与玩家的 foodTickTimer 逻辑一致）────────────────────
        // 条件：饥饿 >= 18（9 格）且生命值未满
        if (hunger >= 18 && getHealth() < getMaxHealth()) {
            regenTickCounter++;
            // 玩家机制：饱和度高时回血更快（每 tick 消耗饱和度），
            // 饱和度低时每 80 tick（4 秒）回 1 HP
            if (saturation > 0) {
                // 饱和度 > 0：快速回血，约每 10 tick（0.5 秒）1 HP
                if (regenTickCounter >= 10) {
                    regenTickCounter = 0;
                    heal(1.0F);
                    // 消耗饱和度（玩家机制：回血消耗 6 点饱和度，但这里是简化的每 tick 消耗）
                    // 实际玩家是每 4 点饱和度回 1 HP，我们用更平滑的方式
                }
                // 消耗饱和度用于回血
                if (regenTickCounter == 0) {
                    saturation = Math.max(saturation - 6.0F, 0.0F);
                }
            } else if (regenTickCounter >= 80) {
                // 饱和度 = 0：慢速回血，每 80 tick（4 秒）1 HP
                regenTickCounter = 0;
                heal(1.0F);
                // 消耗 1 点饥饿值（玩家机制）
                if (hunger > 0) {
                    hunger--;
                }
            }
        } else {
            regenTickCounter = 0;
        }

        // ── 饥饿伤害（困难模式：扣到 1 HP）────────────────────────────────
        if (hunger <= 0) {
            // 每 80 tick（4 秒）受到 1 点伤害，与玩家困难模式一致
            // 使用 tickCount 取模避免额外字段
            if (tickCount % 80 == 0 && getHealth() > 1.0F) {
                hurt(damageSources().starve(), 1.0F);
            }
        }
    }

    /**
     * 增加消耗度（由移动、战斗等活动调用）。
     * 与玩家的 FoodData.addExhaustion() 一致。
     *
     * @param amount 消耗度增量（参见玩家常量：冲刺 0.1/tick，跳跃 0.05，攻击 0.1 等）
     */
    public void addExhaustion(float amount) {
        exhaustion = Math.min(exhaustion + amount, 40.0F);
    }

    /**
     * 自动进食逻辑 — 优先级高于 AI 决策。
     * <p>
     * 触发条件（满足任一）：
     * <ul>
     *   <li>血量不满 且 饥饿不满</li>
     *   <li>饥饿值低于 1/3（小于 7）</li>
     * </ul>
     * 行为：
     * <ul>
     *   <li>空闲时立即吃东西，直到饥饿值补满</li>
     *   <li>背包中没有食物时，向玩家索要</li>
     * </ul>
     */
    private void tickAutoEat() {
        // 冷却递减
        if (askFoodCooldown > 0) {
            askFoodCooldown--;
        }
        if (eatCooldown > 0) {
            eatCooldown--;
        }

        // 检查是否需要进食
        boolean healthNotFull = getHealth() < getMaxHealth();
        boolean hungerNotFull = hunger < MAX_HUNGER;
        boolean hungerLow = hunger < 7; // 低于 1/3

        boolean shouldEat = (healthNotFull && hungerNotFull) || hungerLow;

        if (!shouldEat) return;

        // 如果正在执行其他动作，先取消（进食优先级高）
        // 但不在战斗中取消（避免被打断）
        if (getTarget() != null) return;

        // 尝试找到食物
        if (hasFoodInInventory()) {
            // 有空闲且冷却结束时，启动进食动作（有动画和音效）
            if (actionExecutor.isIdle() && eatCooldown <= 0) {
                actionExecutor.startAction(Action.simple(ActionType.EAT, "自动进食恢复饥饿值"));
                eatCooldown = EAT_COOLDOWN; // 设置冷却，防止连续快速吃东西
            }
        } else {
            // 背包没有食物，向玩家索要（带冷却）
            if (askFoodCooldown <= 0 && actionExecutor.isIdle()) {
                askFoodCooldown = ASK_FOOD_COOLDOWN;
                String name = getName().getString();
                // 根据紧急程度选择不同消息
                String message;
                if (hunger <= 3) {
                    message = "我快饿死了！谁有食物给我一点？";
                } else if (hungerLow) {
                    message = "我饿了，有谁有多余的食物吗？";
                } else {
                    message = "我受伤了需要吃东西恢复，有食物吗？";
                }
                // 广播给附近的玩家
                actionExecutor.broadcast(message);
            }
        }
    }

    /**
     * 检查背包中是否有食物。
     */
    private boolean hasFoodInInventory() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试将 ItemStack 添加到背包中。
     * 返回未能放入的物品。
     */
    public ItemStack addToInventory(ItemStack stack) {
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

    // ── 死亡处理 ─────────────────────────────────────────────────────────────

    @Override
    public void die(DamageSource source) {
        if (level().isClientSide()) {
            super.die(source);
            return;
        }

        // 保存状态到追踪器（用于重生恢复）
        CompanionTracker.get().onCompanionDied(this);

        boolean dropItems = Config.DROP_ITEMS_ON_DEATH.get();

        if (!dropItems) {
            // 不掉落：在 super.die() 处理掉落前清空所有装备和背包
            // 已保存的物品会在重生时恢复
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                setItemSlot(slot, ItemStack.EMPTY);
            }
            inventory.clearContent();
        } else {
            // 掉落：直接创建 ItemEntity 加入世界（匹配原版生物死亡掉落行为）
            // 不使用 spawnAtLocation，因为 die() 期间 captureDrops() 可能非空导致物品被捕获而非生成
            ServerLevel serverLevel = (ServerLevel) level();

            // 先掉落所有装备槽（主手、副手、护甲）— 同样受 captureDrops() 影响，需手动处理
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    dropItemToWorld(serverLevel, stack.copy());
                    setItemSlot(slot, ItemStack.EMPTY);
                }
            }

            // 再掉落自定义背包中的物品
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    dropItemToWorld(serverLevel, stack.copy());
                }
            }
            inventory.clearContent();
        }

        super.die(source);
    }

    /**
     * 直接将物品作为 ItemEntity 生成到世界中，匹配原版生物死亡掉落行为：
     * <ul>
     *   <li>拾取延迟 40 tick（2 秒），与原版生物掉落一致</li>
     *   <li>随机水平速度散开，避免所有物品堆叠在同一点</li>
     *   <li>向上初速度 0.2，模拟掉落弹起</li>
     * </ul>
     * 不使用 {@code spawnAtLocation}，因为 {@code die()} 期间 {@code captureDrops()}
     * 可能返回非空集合，导致物品被捕获而非真正生成到世界中。
     */
    private void dropItemToWorld(ServerLevel level, ItemStack stack) {
        ItemEntity itemEntity = new ItemEntity(
                level,
                this.getX(),
                this.getY() + 0.5,
                this.getZ(),
                stack);
        // 原版生物死亡掉落拾取延迟 = 40 tick（2 秒）
        itemEntity.setPickUpDelay(40);
        // 随机水平散开，模拟原版掉落物的分散效果
        float angle = level.getRandom().nextFloat() * (float) (Math.PI * 2.0);
        float speed = level.getRandom().nextFloat() * 0.2F;
        itemEntity.setDeltaMovement(
                Math.cos(angle) * speed,
                0.2,
                Math.sin(angle) * speed);
        level.addFreshEntity(itemEntity);
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
        // 保存饥饿系统（与玩家 FoodData 一致）
        output.putInt("Hunger", hunger);
        output.putFloat("Saturation", saturation);
        output.putFloat("Exhaustion", exhaustion);
        // 保存行为模式
        output.putString("Mode", mode.name());
        // 保存拥有者
        if (ownerUuid != null) {
            output.putString("OwnerUUID", ownerUuid.toString());
        }
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
        // 加载饥饿系统
        hunger = input.getIntOr("Hunger", MAX_HUNGER);
        saturation = input.getFloatOr("Saturation", 5.0F);
        exhaustion = input.getFloatOr("Exhaustion", 0.0F);
        // 加载行为模式
        String modeStr = input.getStringOr("Mode", CompanionMode.FOLLOW.name());
        try {
            mode = CompanionMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            mode = CompanionMode.FOLLOW;
        }
        // 加载拥有者
        String ownerStr = input.getStringOr("OwnerUUID", "");
        if (!ownerStr.isEmpty()) {
            try {
                ownerUuid = UUID.fromString(ownerStr);
            } catch (IllegalArgumentException e) {
                ownerUuid = null;
            }
        }
    }

    /**
     * 获取同伴的背包（27 格，类似玩家的主背包）
     */
    public SimpleContainer getInventory() {
        return inventory;
    }

    // ── MenuProvider 实现 — Shift+右键打开背包 GUI ─────────────────────────

    /**
     * 右键交互处理。
     * <ul>
     *   <li>Shift + 右键：打开同伴背包 GUI</li>
     *   <li>普通右键：保持默认行为</li>
     * </ul>
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide()) return InteractionResult.PASS;

        // 防止 MC 单次右键多次触发交互（同一 tick 内只处理一次）
        int currentTick = tickCount;
        if (currentTick == lastInteractTick) return InteractionResult.PASS;
        lastInteractTick = currentTick;

        if (player.isShiftKeyDown()) {
            // Shift + 右键：打开背包 GUI
            player.openMenu(this, buf -> buf.writeVarInt(getId()));
            return InteractionResult.CONSUME;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            // 空手右键：切换行为模式
            CompanionMode oldMode = mode;
            mode = mode.next();
            // 切换模式时重置当前动作
            if (oldMode != mode) {
                actionExecutor.cancel();
            }
            String msg = "[" + getName().getString() + "] 模式切换: "
                    + oldMode.getDisplayName() + " → " + mode.getDisplayName();
            player.sendSystemMessage(Component.literal(msg));
            return InteractionResult.CONSUME;
        }

        return super.mobInteract(player, hand);
    }

    /** 背包 GUI 的标题 — 使用同伴的名字 */
    @Override
    public Component getDisplayName() {
        return getName();
    }

    /** 服务端：创建背包菜单实例 */
    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId,
                                                       Inventory playerInv,
                                                       Player player) {
        return new CompanionInventoryMenu(containerId, playerInv, this);
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

    /** 获取当前饱和度（0-20） */
    public float getSaturation() {
        return saturation;
    }

    /** 设置饱和度，限制在 [0, hunger] 范围内（与玩家一致：饱和度不能超过饥饿值） */
    public void setSaturation(float value) {
        this.saturation = Math.max(0, Math.min(value, hunger));
    }

    /** 获取当前消耗度（0-4） */
    public float getExhaustion() {
        return exhaustion;
    }

    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    public AITickHandler getAiTickHandler() {
        return aiTickHandler;
    }

    public CompanionMode getMode() {
        return mode;
    }

    public void setMode(CompanionMode mode) {
        this.mode = mode;
    }

    public @Nullable UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(@Nullable UUID uuid) {
        this.ownerUuid = uuid;
    }

    /**
     * 获取拥有者玩家实体（如果在线且在同一维度）。
     */
    public @Nullable ServerPlayer getOwner() {
        if (ownerUuid == null) return null;
        return ((net.minecraft.server.level.ServerLevel) level())
                .getServer().getPlayerList().getPlayer(ownerUuid);
    }
}
