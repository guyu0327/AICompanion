package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * 战斗动作控制器 — 处理 {@code ATTACK} 动作的完整战斗逻辑：
 * 近战/远程武器自动选择、弓箭蓄力射击、弹道预测、自我保护撤退。
 * <p>
 * 武器选择支持模式锁（滞后效应），防止在距离阈值边界
 * 每 tick 来回切换武器。
 */
class CombatController extends ActionController {

    private static final double MOVE_SPEED = 1.0;
    private static final double ATTACK_REACH = 3.0;
    private static final float ATTACK_DAMAGE = 4.0F;
    private static final int ATTACK_COOLDOWN = 14;     // 两次攻击之间的 tick 数（约 0.7 秒）
    private static final int ENTITY_SCAN_RANGE = 24;
    private static final double RANGED_THRESHOLD = 6.0; // 超过此距离优先使用远程武器
    private static final float BOW_ATTACK_SPEED = 1.6F; // 弓箭基础飞行速度
    private static final int BOW_CHARGE_DURATION = 20;  // 弓箭蓄力 tick 数（1 秒 = 满弦）
    private static final float ARROW_GRAVITY = 0.05F;   // 箭矢每 tick 下落量（MC 原版值）
    private static final float ARROW_DRAG = 0.99F;      // 箭矢每 tick 速度衰减（MC 原版值）

    // 武器模式锁定 — 防止在阈值边界来回切换武器（每 tick 换剑/弓）
    private static final int MODE_LOCK_DURATION = 20;   // 锁定 1 秒
    // 武器评估节流 — 每 10 tick 重新评估一次武器选择（0.5 秒足够响应战况）
    private static final int WEAPON_EVAL_INTERVAL = 10;
    // 撤退冷却 — 撤退后一段时间内不再重复播报和启动攻击（3 秒）
    private static final int RETREAT_COOLDOWN = 60;

    // ── 战斗状态字段 ─────────────────────────────────────────────────────────
    private Entity attackTarget;
    private int attackCooldown;
    private boolean bowCharging = false;   // 是否正在蓄力弓箭
    private Boolean lastRangedMode = null; // 上次决定的模式（true=远程，false=近战）
    private int modeLockRemaining = 0;     // 模式锁剩余 tick 数
    // 武器播报去重 — 只在武器真正更换时播报一次
    private String lastAnnouncedWeaponId = "";
    // 战斗播报去重 — 同一目标不重复播报"发起攻击"
    private String lastAnnouncedTargetId = "";
    private int ticksSinceLastWeaponEval = 0;
    // 撤退冷却 — > 0 时表示正在撤退中，不再播报也不再启动新攻击
    private int retreatCooldown = 0;
    // 盔甲播报去重 — 每个槽位记录上次装备的盔甲 ID
    private final String[] lastAnnouncedArmorIds = new String[4]; // HEAD, CHEST, LEGS, FEET

    CombatController(AICompanionEntity companion, ChatHistory chatHistory, ActionExecutor executor) {
        super(companion, chatHistory, executor);
    }

    // ── 公共 API ─────────────────────────────────────────────────────────────

    /**
     * 自动从背包装备最适合当前战斗场景的武器（由 AICompanionEntity.tick 每 tick 调用）。
     * <ul>
     *   <li>目标较远 / 有高低差 / 无法到达 且背包有箭矢 → 优先使用远程武器（弓、弩）</li>
     *   <li>目标近距离可到达 / 箭矢耗尽 → 优先使用近战武器（剑、斧、三叉戟、锤）</li>
     * </ul>
     */
    public void autoEquipWeapon(Entity target) {
        int arrows = countArrowsInInventory();
        boolean useRanged = target != null && shouldUseRanged(target);

        ItemStack held = companion.getMainHandItem();
        String heldId = held.isEmpty() ? ""
                : held.getItem().builtInRegistryHolder().key().identifier().getPath();
        boolean heldMatchesMode = useRanged ? isRangedWeapon(held) : isMeleeWeapon(held);

        AICompanion.LOGGER.debug("[Weapon] arrows={}, useRanged={}, held={}, heldMatchesMode={}",
                arrows, useRanged, heldId, heldMatchesMode);

        // 当前手持武器已符合战斗模式，跳过
        if (heldMatchesMode) return;

        float bestDamage = -1.0F;
        int bestSlot = -1;

        for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
            ItemStack stack = companion.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            // 只考虑符合当前战斗模式的武器
            if (useRanged ? !isRangedWeapon(stack) : !isMeleeWeapon(stack)) continue;

            float dmg = getWeaponAttackDamage(stack);
            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestSlot = i;
            }
        }

        // 当前模式无合适武器时，回退到任意伤害武器
        if (bestSlot < 0) {
            for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
                ItemStack stack = companion.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                if (!isDamageWeapon(stack)) continue;
                float dmg = getWeaponAttackDamage(stack);
                if (dmg > bestDamage) {
                    bestDamage = dmg;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0) {
            ItemStack weapon = companion.getInventory().getItem(bestSlot);
            ItemStack oldHand = companion.getMainHandItem().copy();
            companion.setItemInHand(InteractionHand.MAIN_HAND, weapon.copy());
            companion.getInventory().setItem(bestSlot, ItemStack.EMPTY);
            if (!oldHand.isEmpty()) {
                companion.getInventory().setItem(bestSlot, oldHand);
            }
            String weaponId = companion.getMainHandItem().getItem()
                    .builtInRegistryHolder().key().identifier().getPath();
            // 只在武器真正更换时播报，避免每 tick 重复刷屏
            if (!weaponId.equals(lastAnnouncedWeaponId)) {
                lastAnnouncedWeaponId = weaponId;
                executor.announce("装备了 " + weaponId);
            }
        } else {
            AICompanion.LOGGER.debug("[Weapon] 背包中没有合适的 {} 武器",
                    useRanged ? "远程" : "近战");
        }
    }

    /**
     * 自动从背包装备最佳盔甲（由 AICompanionEntity.tick 定期调用）。
     * 对每个盔甲槽位（头/胸/腿/脚），比较背包中的盔甲与已装备的盔甲，
     * 如果背包中有更好的（护甲值 + 韧性更高），则自动替换。
     */
    public void autoEquipArmor() {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {

            ItemStack current = companion.getItemBySlot(slot);
            float currentScore = getArmorScore(current, slot);

            int bestSlot = -1;
            float bestScore = currentScore;

            // 在背包中寻找更好的盔甲
            for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
                ItemStack stack = companion.getInventory().getItem(i);
                if (stack.isEmpty()) continue;

                // 检查这件物品是否是该槽位的盔甲（有该槽位的护甲属性）
                float score = getArmorScore(stack, slot);
                if (score <= 0) continue; // 不是该槽位的盔甲或没有护甲值

                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }

            // 找到更好的盔甲，执行替换
            if (bestSlot >= 0) {
                ItemStack newArmor = companion.getInventory().getItem(bestSlot);
                ItemStack oldArmor = current.copy();

                // 装备新盔甲
                companion.setItemSlot(slot, newArmor.copy());
                companion.getInventory().setItem(bestSlot, ItemStack.EMPTY);

                // 将旧盔甲放回背包
                if (!oldArmor.isEmpty()) {
                    companion.getInventory().setItem(bestSlot, oldArmor);
                }

                // 播报（仅当盔甲真正变化时）
                String armorId = newArmor.getItem()
                        .builtInRegistryHolder().key().identifier().getPath();
                int slotIndex = switch (slot) {
                    case HEAD -> 0;
                    case CHEST -> 1;
                    case LEGS -> 2;
                    case FEET -> 3;
                    default -> -1;
                };
                if (slotIndex >= 0 && !armorId.equals(lastAnnouncedArmorIds[slotIndex])) {
                    lastAnnouncedArmorIds[slotIndex] = armorId;
                    executor.announce("装备了 " + armorId);
                }
            }
        }
    }

    // ── 动作生命周期 ──────────────────────────────────────────────────────────

    /** 每 tick 递减撤退冷却（由 ActionExecutor.tick 调用） */
    void tickCooldown() {
        if (retreatCooldown > 0) retreatCooldown--;
    }

    /** 正在撤退冷却中时返回 true（阻止启动新的攻击动作） */
    public boolean isRetreating() {
        return retreatCooldown > 0;
    }

    void beginAttack(Action action) {
        String targetName = action.getTargetEntityName();
        if (targetName == null || targetName.isBlank()) {
            AICompanion.LOGGER.warn("[AI] ATTACK action missing targetName");
            executor.completeAction();
            return;
        }
        attackTarget = findEntityByName(targetName);
        if (attackTarget == null) {
            executor.announce("没有找到 " + targetName);
            executor.completeAction();
            return;
        }
        // 攻击前自动从背包中装备最佳武器（根据距离决定近战/远程）
        autoEquipWeapon(attackTarget);

        attackCooldown = 0;
        executor.setState(ActionExecutor.State.ATTACKING);
        // 同一目标只播报一次"发现/发起攻击"
        if (!targetName.equals(lastAnnouncedTargetId)) {
            lastAnnouncedTargetId = targetName;
            executor.announce("发现 " + targetName + "，发起攻击！");
        }
    }

    void tickAttack() {
        if (attackTarget == null || attackTarget.isRemoved()) {
            executor.announce("目标消失了");
            executor.completeAction();
            return;
        }

        // ── 自我保护：低血量时撤退 ──────────────────────────────────────────
        if (shouldRetreat()) {
            // 只在首次撤退时播报一次，避免循环触发刷屏
            if (retreatCooldown <= 0) {
                executor.announce("血量过低，撤退！");
                retreatCooldown = RETREAT_COOLDOWN;
            }
            retreatFromTarget();
            executor.completeAction();
            return;
        }

        ServerLevel level = (ServerLevel) companion.level();
        double dist = companion.distanceTo(attackTarget);

        // 每 10 tick 评估一次武器选择（0.5 秒足够响应战况变化，避免每 tick 遍历背包）
        ticksSinceLastWeaponEval++;
        if (ticksSinceLastWeaponEval >= WEAPON_EVAL_INTERVAL) {
            autoEquipWeapon(attackTarget);
            ticksSinceLastWeaponEval = 0;
        }

        // 判断是否正在使用远程武器（同时确认背包有箭，防止无箭时卡在弓蓄力分支）
        boolean isRanged = isRangedWeapon(companion.getMainHandItem()) && countArrowsInInventory() > 0;

        // 如果从远程切到近战（箭耗尽 / 距离近），停止正在进行的弓蓄力
        if (!isRanged && bowCharging) {
            companion.stopUsingItem();
            bowCharging = false;
        }

        // ── 远程武器逻辑（弓箭蓄力射击）───────────────────────────────────
        if (isRanged) {
            // 保持距离（太近时后退，最佳射程约 8-12 格）
            if (dist < 5.0) {
                // 后退：朝远离目标的方向移动
                double dx = companion.getX() - attackTarget.getX();
                double dz = companion.getZ() - attackTarget.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01) {
                    companion.getNavigation().moveTo(
                            companion.getX() + dx / len * 2,
                            companion.getY(),
                            companion.getZ() + dz / len * 2,
                            MOVE_SPEED * 0.8);
                }
            } else if (dist > 15.0) {
                // 太远，靠近一些
                companion.getNavigation().moveTo(attackTarget, MOVE_SPEED);
            }

            // 面向目标（提前量修正：朝目标预测位置瞄准）
            companion.getLookControl().setLookAt(attackTarget, 30F, 30F);

            // ── 弓箭蓄力状态机 ──────────────────────────────────────────────
            if (!bowCharging) {
                // 冷却中，等待
                if (attackCooldown > 0) {
                    attackCooldown--;
                    return;
                }
                // 视线检查 — 确保没有方块阻挡再开始蓄力
                if (!hasLineOfSight(attackTarget)) return;
                // 开始蓄力（拉弓动作，持续 BOW_CHARGE_DURATION ticks）
                companion.startUsingItem(InteractionHand.MAIN_HAND);
                bowCharging = companion.isUsingItem();
                AICompanion.LOGGER.debug("[Bow] startUsingItem, isUsingAfter={}, item={}",
                        bowCharging, companion.getMainHandItem().getItem()
                                .builtInRegistryHolder().key().identifier().getPath());
                return;
            }

            // 蓄力中 — 检查是否已蓄满（原版满弦 = 20 ticks / 1 秒）
            int ticksUsing = companion.getTicksUsingItem();
            boolean isUsing = companion.isUsingItem();
            AICompanion.LOGGER.debug("[Bow] charging: ticksUsing={}, isUsing={}, duration={}",
                    ticksUsing, isUsing, BOW_CHARGE_DURATION);
            if (ticksUsing < BOW_CHARGE_DURATION) {
                return; // 继续蓄力，等待
            }

            // 蓄满！释放箭矢
            AICompanion.LOGGER.debug("[Bow] 蓄满释放！ticksUsing={}, power={}",
                    ticksUsing, Math.min(1.0F, ticksUsing / 20.0F));
            companion.stopUsingItem();
            bowCharging = false;

            // 计算蓄力强度 power：原版公式
            // power = (ticksUsing / 20) clamped to [0.1, 1.0]
            float power = Math.min(1.0F, ticksUsing / 20.0F);
            power = Math.max(0.1F, power);

            fireArrow(level, power);
            attackCooldown = ATTACK_COOLDOWN;
            return;
        }

        // ── 近战武器逻辑 ────────────────────────────────────────────────────
        // 追击
        if (dist > ATTACK_REACH) {
            companion.getNavigation().moveTo(attackTarget, MOVE_SPEED);
            return;
        }

        // 面向目标
        companion.getLookControl().setLookAt(attackTarget, 30F, 30F);

        // 两次攻击之间的冷却
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }

        // 攻击！使用实际攻击伤害（考虑装备的武器加成）
        float actualDamage = getCompanionAttackDamage();
        boolean hit = attackTarget.hurtServer(
                level,
                level.damageSources().mobAttack(companion),
                actualDamage);
        companion.swing(InteractionHand.MAIN_HAND);
        attackCooldown = ATTACK_COOLDOWN;

        if (hit) {
            AICompanion.LOGGER.debug("[AI] 击中 {} (剩余 HP: {})",
                    attackTarget.getName().getString(),
                    attackTarget instanceof net.minecraft.world.entity.LivingEntity le
                            ? le.getHealth() : "?");
        }

        if (attackTarget.isRemoved()
                || (attackTarget instanceof net.minecraft.world.entity.LivingEntity le
                && le.isDeadOrDying())) {
            executor.completeAction();
        }
    }

    /**
     * 取消战斗动作时的清理：停止弓箭蓄力、重置模式锁和播报去重。
     */
    void cleanup() {
        if (bowCharging) {
            companion.stopUsingItem();
            bowCharging = false;
        }
        lastRangedMode = null;
        modeLockRemaining = 0;
        lastAnnouncedWeaponId = "";
        lastAnnouncedTargetId = "";
        // 重置盔甲播报去重
        for (int i = 0; i < lastAnnouncedArmorIds.length; i++) {
            lastAnnouncedArmorIds[i] = null;
        }
    }

    // ── 武器选择辅助 ─────────────────────────────────────────────────────────

    /**
     * 判断是否应使用远程武器。
     * 带模式锁（滞后效应）：选定模式后锁定 {@value #MODE_LOCK_DURATION} tick，
     * 防止目标在阈值边界时武器每 tick 来回切换。
     * <p>
     * 强制切换（无视锁定）：
     * <ul>
     *   <li>箭矢耗尽 → 强制近战</li>
     *   <li>目标已进入近战范围（&lt; {@value #ATTACK_REACH} 格）→ 强制近战</li>
     * </ul>
     */
    private boolean shouldUseRanged(Entity target) {
        // 没有箭矢 → 必须近战
        int arrows = countArrowsInInventory();
        if (arrows <= 0) {
            AICompanion.LOGGER.debug("[Weapon] 无箭矢，强制近战 (arrows={})", arrows);
            lastRangedMode = false;
            modeLockRemaining = MODE_LOCK_DURATION;
            return false;
        }

        double dist = companion.distanceTo(target);
        double dy = Math.abs(companion.getY() - target.getY());

        // 目标已在近战攻击范围内 → 必须近战（不论锁定）
        if (dist <= ATTACK_REACH) {
            lastRangedMode = false;
            modeLockRemaining = MODE_LOCK_DURATION;
            return false;
        }

        // 模式锁生效期内 → 维持上次决定
        if (modeLockRemaining > 0 && lastRangedMode != null) {
            modeLockRemaining--;
            return lastRangedMode;
        }

        // 评估是否应使用远程
        boolean useRanged;
        if (lastRangedMode != null && lastRangedMode) {
            // 当前为远程模式 → 用更低的阈值才切回（滞后）
            // 只有目标非常近且高低差小时才切回近战，防止在阈值附近振荡
            useRanged = dist > (RANGED_THRESHOLD * 0.5) || dy > 2.0;
            if (!useRanged) {
                AICompanion.LOGGER.debug("[Weapon] 远程→近战 (dist={}, threshold={})",
                        dist, RANGED_THRESHOLD * 0.5);
            }
        } else {
            // 当前为近战模式或首次决定 → 正常阈值
            if (dist > RANGED_THRESHOLD) {
                useRanged = true;
            } else if (dy > 2.0) {
                useRanged = true;
            } else {
                // 无法规划到目标的路径（被方块阻挡）
                var path = companion.getNavigation().createPath(target, 0);
                useRanged = (path == null || !path.canReach());
                if (useRanged) {
                    AICompanion.LOGGER.debug("[Weapon] 无法到达目标，使用远程");
                }
            }
        }

        lastRangedMode = useRanged;
        modeLockRemaining = MODE_LOCK_DURATION;
        return useRanged;
    }

    /**
     * 统计同伴背包中的箭矢总数。
     * 同时计算主手持有的箭（副手暂不计）。
     */
    private int countArrowsInInventory() {
        int count = 0;
        for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
            ItemStack stack = companion.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ARROW) {
                count += stack.getCount();
            }
        }
        // 主手也拿着箭时计入
        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem() == Items.ARROW) {
            count += mainHand.getCount();
        }
        return count;
    }

    /**
     * 根据物品判断是否为伤害类武器（近战或远程）。
     */
    private boolean isDamageWeapon(ItemStack stack) {
        return isMeleeWeapon(stack) || isRangedWeapon(stack);
    }

    /**
     * 判断是否为远程武器（弓、弩）。
     */
    private boolean isRangedWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem;
    }

    /**
     * 判断是否为近战武器（剑、斧、三叉戟、锤）。
     * 使用原版 Tag 判断以自动支持 mod 武器。
     */
    private boolean isMeleeWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || stack.is(Items.TRIDENT)
                || stack.is(Items.MACE);
    }

    /**
     * 读取物品的 {@link ItemAttributeModifiers} 组件中的攻击伤害值。
     * 无属性数据的物品返回 1.0（空手伤害）。
     */
    private float getWeaponAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) return 1.0F;
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 1.0F;

        float damage = 1.0F;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            String attrId = entry.attribute().getRegisteredName();
            if (attrId.contains("attack_damage")) {
                AttributeModifier mod = entry.modifier();
                damage += (float) mod.amount();
            }
        }
        return damage;
    }

    /**
     * 获取同伴的实际攻击伤害（含装备武器加成）。
     * 通过 AttributeMap 读取 MOB 属性，主手武器的加成会自动计入。
     */
    private float getCompanionAttackDamage() {
        var attr = companion.getAttributes().getInstance(Attributes.ATTACK_DAMAGE);
        return attr != null ? (float) attr.getValue() : ATTACK_DAMAGE;
    }

    /**
     * 计算盔甲在指定槽位的综合防护分数（护甲值 + 韧性）。
     * 仅计算适用于该 equipmentSlot 的属性。
     * 空物品或非该槽位盔甲返回 0。
     */
    private float getArmorScore(ItemStack stack, EquipmentSlot equipmentSlot) {
        if (stack.isEmpty()) return 0.0F;
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 0.0F;

        float score = 0.0F;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            // 只计算适用于该槽位的属性（EquipmentSlotGroup 包含该 EquipmentSlot）
            if (!entry.slot().test(equipmentSlot)) continue;

            String attrId = entry.attribute().getRegisteredName();
            AttributeModifier mod = entry.modifier();
            // 护甲值 (armor) 和韧性 (armor_toughness) 都计入评分
            if (attrId.contains("armor") || attrId.contains("toughness")) {
                score += (float) mod.amount();
            }
        }
        return score;
    }

    // ── 弓箭射击 ─────────────────────────────────────────────────────────────

    /**
     * 发射箭矢。从背包或主手中消耗一支箭，生成箭矢实体并射向目标。
     *
     * @param power 蓄力强度（0.1 到 1.0），与原版弓蓄力公式一致：
     *              影响箭矢飞行速度和伤害（power² × 伤害系数）。
     */
    private void fireArrow(ServerLevel level, float power) {
        // 从背包或主手中消耗一支箭
        ItemStack arrowStack = ItemStack.EMPTY;
        // 先查背包
        for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
            ItemStack stack = companion.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ARROW) {
                arrowStack = stack.copyWithCount(1);
                stack.shrink(1);
                break;
            }
        }
        // 背包无箭时查主手
        if (arrowStack.isEmpty()) {
            ItemStack mainHand = companion.getMainHandItem();
            if (!mainHand.isEmpty() && mainHand.getItem() == Items.ARROW) {
                arrowStack = mainHand.copyWithCount(1);
                mainHand.shrink(1);
            }
        }

        // 无箭矢可射（异常分支：正常流程不应到达此处）
        if (arrowStack.isEmpty()) return;

        // 创建箭矢实体
        Arrow arrow = new Arrow(level, companion, arrowStack, companion.getMainHandItem());
        arrow.setPos(companion.getEyePosition());
        arrow.setOwner(companion);

        // ── 弹道预测瞄准（神射手级精度）─────────────────────────────────────
        // 计算击中目标所需的精确初速度，补偿重力和空气阻力
        double targetX = attackTarget.getX();
        double targetY = attackTarget.getY() + attackTarget.getBbHeight() * 0.5;
        double targetZ = attackTarget.getZ();
        double dx = targetX - arrow.getX();
        double dy = targetY - arrow.getY();
        double dz = targetZ - arrow.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float speed = BOW_ATTACK_SPEED * power; // 箭矢初速（满弦 = 1.6）
        // 估算飞行时间（基于水平距离和初速）
        double flightTime = Math.max(1.0, horizontalDist / speed);

        // 物理公式：y(t) = vy * Σ(drag^i, i=0..t-1) - gravity * Σ(i * drag^(t-1-i), i=0..t-1)
        // 化简：vy * (1-drag^t)/(1-drag) - gravity * 修正项 = H
        // 求解 vy：vy = (H + gravity * 修正项) * (1-drag) / (1-drag^t)
        double dragPow = Math.pow(ARROW_DRAG, flightTime);
        double gravityCorrection = ARROW_GRAVITY * (flightTime - (1 - dragPow) / (1 - ARROW_DRAG));
        double vy = (dy + gravityCorrection) * (1 - ARROW_DRAG) / (1 - dragPow);

        // 水平速度：确保在飞行时间内覆盖水平距离
        double horizontalSpeed = horizontalDist * (1 - ARROW_DRAG) / (1 - dragPow);
        double vx = 0, vz = 0;
        if (horizontalDist > 0.001) {
            vx = dx / horizontalDist * horizontalSpeed;
            vz = dz / horizontalDist * horizontalSpeed;
        }

        // 直接设置精确速度（无随机散布，神射手级命中）
        arrow.setDeltaMovement(vx, vy, vz);

        // 箭矢伤害 = 蓄力强度² × 伤害系数（原版公式）
        // power=1 时：2~9 点伤害（随机）+ 武器伤害加成
        // power=0.5 时：伤害约为满弦的 1/4
        float damageMultiplier = power * power * 7.0F + 2.0F;
        float weaponDamage = getWeaponAttackDamage(companion.getMainHandItem());
        arrow.setBaseDamage(Math.max(2.0, damageMultiplier + weaponDamage * power));

        level.addFreshEntity(arrow);
        companion.swing(InteractionHand.MAIN_HAND);

        // 播放射击音效
        level.playSound(null, companion.getX(), companion.getY(), companion.getZ(),
                SoundEvents.ARROW_SHOOT,
                SoundSource.HOSTILE,
                1.0F, 1.0F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    // ── 自我保护 ─────────────────────────────────────────────────────────────

    /**
     * 判断同伴是否应该撤退。
     * 条件：血量低于最大值的 30%。
     */
    private boolean shouldRetreat() {
        return companion.getHealth() < companion.getMaxHealth() * 0.3F;
    }

    /**
     * 从当前攻击目标方向撤退（向反方向移动一段距离）。
     */
    private void retreatFromTarget() {
        if (attackTarget == null || attackTarget.isRemoved()) return;
        double dx = companion.getX() - attackTarget.getX();
        double dz = companion.getZ() - attackTarget.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            // 正重叠，随机方向
            dx = 1;
            dz = 0;
            len = 1;
        }
        // 向反方向移动 8 格
        companion.getNavigation().moveTo(
                companion.getX() + dx / len * 8,
                companion.getY(),
                companion.getZ() + dz / len * 8,
                MOVE_SPEED * 1.2);
    }

    private boolean hasLineOfSight(Entity target) {
        return companion.hasLineOfSight(target);
    }

    // ── 实体查找 ─────────────────────────────────────────────────────────────

    /** 查找类型 ID 包含 {@code name} 的最近实体（不区分大小写） */
    private Entity findEntityByName(String name) {
        ServerLevel level = (ServerLevel) companion.level();
        AABB box = companion.getBoundingBox().inflate(ENTITY_SCAN_RANGE);
        Predicate<Entity> predicate = e -> true;
        List<Entity> entities = level.getEntities((Entity) null, box, predicate);

        Entity best = null;
        double bestDst = Double.MAX_VALUE;
        String lower = name.toLowerCase();

        for (Entity e : entities) {
            if (e == companion) continue;
            String id = e.getType().builtInRegistryHolder().key().identifier().toString();
            String disp = e.getName().getString();
            if (id.toLowerCase().contains(lower) || disp.toLowerCase().contains(lower)) {
                double d = companion.distanceToSqr(e);
                if (d < bestDst) {
                    bestDst = d;
                    best = e;
                }
            }
        }
        return best;
    }
}
