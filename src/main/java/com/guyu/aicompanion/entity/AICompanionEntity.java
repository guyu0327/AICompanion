package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.Config;
import com.guyu.aicompanion.action.Action;
import com.guyu.aicompanion.action.ActionExecutor;
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

    /** 饥饿等级（0-20，类似玩家）。随时间减少；降到 0 时同伴会受伤。 */
    private int hunger = 20;
    private static final int MAX_HUNGER = 20;

    /** 饥饿计时器 — 每 600 ticks（30 秒）减少 1 点饥饿值 */
    private int hungerTickCounter = 0;
    private static final int HUNGER_TICK_INTERVAL = 600;

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

            // 饥饿计时
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
        // 保存饥饿值
        output.putInt("Hunger", hunger);
        output.putInt("HungerTickCounter", hungerTickCounter);
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
        // 加载饥饿值
        hunger = input.getIntOr("Hunger", MAX_HUNGER);
        hungerTickCounter = input.getIntOr("HungerTickCounter", 0);
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
