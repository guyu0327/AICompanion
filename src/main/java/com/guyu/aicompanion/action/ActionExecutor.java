package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * 在游戏世界中执行 AI 决策的动作
 * <p>
 * 用法：在 {@code AICompanionEntity.tick()} 中每个服务器 tick 调用一次 {@link #tick()}。
 * 当 AI 大脑返回新决策时调用 {@link #startAction(Action)}。
 */
public class ActionExecutor {

    // ── 执行状态 ────────────────────────────────────────────────────────────

    public enum State {
        IDLE,       // 空闲
        MOVING,     // 移动中
        MINING,     // 挖掘中
        ATTACKING,  // 攻击中
        WAITING     // 等待中
    }

    // ── 调优常量 ────────────────────────────────────────────────────────────

    private static final double MOVE_SPEED         = 1.0;
    private static final double ARRIVAL_THRESHOLD  = 2.0;   // 方块 — 移动到此范围内视为到达
    private static final double MINE_REACH         = 2.5;   // 必须在此范围内才能开始挖掘
    private static final double ATTACK_REACH       = 3.0;
    private static final float  ATTACK_DAMAGE      = 4.0F;
    private static final int    ATTACK_COOLDOWN    = 14;    // 两次攻击之间的 tick 数（约 0.7 秒）
    private static final int    DEFAULT_WAIT       = 40;    // tick 数（约 2 秒）
    private static final int    ENTITY_SCAN_RANGE  = 24;
    private static final double RANGED_THRESHOLD   = 6.0;   // 超过此距离优先使用远程武器
    private static final float  BOW_ATTACK_SPEED   = 1.6F;  // 弓箭基础飞行速度
    private static final int    BOW_CHARGE_DURATION = 20;   // 弓箭蓄力 tick 数（1 秒 = 满弦）
    private static final float  ARROW_GRAVITY      = 0.05F; // 箭矢每 tick 下落量（MC 原版值）
    private static final float  ARROW_DRAG         = 0.99F; // 箭矢每 tick 速度衰减（MC 原版值）

    // ── 字段 ────────────────────────────────────────────────────────────────

    private final net.minecraft.world.entity.Mob companion;
    private final ChatHistory chatHistory;

    private State  state         = State.IDLE;
    private Action currentAction = null;

    // 移动相关
    private BlockPos moveTarget;

    // 挖掘相关
    private BlockPos mineTarget;
    private int      mineProgress;
    private int      mineTotalTicks;

    // 攻击相关
    private Entity attackTarget;
    private int    attackCooldown;
    private boolean bowCharging = false;  // 是否正在蓄力弓箭
    // 武器模式锁定 — 防止在阈值边界来回切换武器（每 tick 换剑/弓）
    private Boolean lastRangedMode   = null;   // 上次决定的模式（true=远程，false=近战）
    private int     modeLockRemaining = 0;      // 模式锁剩余 tick 数
    private static final int MODE_LOCK_DURATION = 20;  // 锁定 1 秒
    // 武器播报去重 — 只在武器真正更换时播报一次
    private String  lastAnnouncedWeaponId = "";
    // 战斗播报去重 — 同一目标不重复播报"发起攻击"
    private String  lastAnnouncedTargetId = "";

    // 等待相关
    private int waitRemaining;

    // 最近的聊天消息（由 ChatHandler / AIService 外部设置）
    public String lastPlayerMessage;
    public String lastChatMessage;

    // ── 构造函数 ────────────────────────────────────────────────────────────

    public ActionExecutor(net.minecraft.world.entity.Mob companion, ChatHistory chatHistory) {
        this.companion = companion;
        this.chatHistory = chatHistory;
    }

    // ── 公开 API ────────────────────────────────────────────────────────────

    /**
     * 开始执行新动作。会取消之前正在运行的任何动作。
     */
    public void startAction(Action action) {
        if (action == null || action.getType() == null) return;
        AICompanion.LOGGER.debug("[AI] 开始执行: {}", action);
        cleanupCurrentAction();
        currentAction = action;
        beginAction(action);
    }

    /** 停止当前运行的任何动作，回到 IDLE 状态 */
    public void cancel() {
        cleanupCurrentAction();
        currentAction = null;
        state = State.IDLE;
    }

    /** 当前没有动作在执行时返回 true */
    public boolean isIdle() {
        return state == State.IDLE;
    }

    public State getState()          { return state; }
    public Action getCurrentAction() { return currentAction; }

    /**
     * 驱动状态机。每个服务器 tick 调用一次。
     */
    public void tick() {
        if (companion.level().isClientSide()) return;
        if (state == State.IDLE) return;

        switch (state) {
            case MOVING    -> tickMove();
            case MINING    -> tickMine();
            case ATTACKING -> tickAttack();
            case WAITING   -> tickWait();
            default        -> {}
        }
    }

    // ── 开始执行动作 ────────────────────────────────────────────────────────

    private void beginAction(Action action) {
        switch (action.getType()) {
            case MOVE        -> beginMove(action);
            case MINE        -> beginMine(action);
            case ATTACK      -> beginAttack(action);
            case CHAT        -> executeChat(action);
            case EAT         -> executeEat();
            case SLEEP       -> executeSleep();
            case WAKE_UP     -> executeWakeUp();
            case DROP_ITEM   -> executeDropItem();
            case USE_ITEM    -> executeUseItem();
            case PLACE_BLOCK -> executePlace(action);
            case WAIT        -> beginWait();
        }
    }

    // ── 移动 ────────────────────────────────────────────────────────────────

    private void beginMove(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] MOVE action missing target position");
            completeAction();
            return;
        }
        moveTarget = action.getTargetPos();
        PathNavigation nav = companion.getNavigation();
        boolean started = nav.moveTo(
                moveTarget.getX() + 0.5,
                moveTarget.getY(),
                moveTarget.getZ() + 0.5,
                MOVE_SPEED
        );
        if (started) {
            state = State.MOVING;
            announce("正在前往 " + moveTarget.toShortString());
        } else {
            AICompanion.LOGGER.warn("[AI] 无法规划到 {} 的路径", moveTarget.toShortString());
            announce("找不到去那里的路");
            completeAction();
        }
    }

    private void tickMove() {
        if (moveTarget == null) { completeAction(); return; }

        // 路径丢失或卡住
        if (companion.getNavigation().isDone()) {
            double dist = companion.position().distanceTo(
                    net.minecraft.world.phys.Vec3.atCenterOf(moveTarget));
            if (dist <= ARRIVAL_THRESHOLD) {
                announce("已到达 " + moveTarget.toShortString());
                completeAction();
            } else {
                // 尝试重新寻路
                boolean repath = companion.getNavigation().moveTo(
                        moveTarget.getX() + 0.5, moveTarget.getY(),
                        moveTarget.getZ() + 0.5, MOVE_SPEED);
                if (!repath) {
                    announce("卡住了，无法到达目的地");
                    completeAction();
                }
            }
        }
    }

    // ── 挖掘 ────────────────────────────────────────────────────────────────

    private void beginMine(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] MINE action missing target position");
            completeAction();
            return;
        }
        mineTarget     = action.getTargetPos();

        // 挖掘前自动从背包装备最佳工具
        autoEquipToolForBlock(mineTarget);

        mineProgress   = 0;
        mineTotalTicks = calculateMineTicks(mineTarget);

        if (mineTotalTicks >= Integer.MAX_VALUE) {
            announce("这个方块挖不动（基岩等）");
            completeAction();
            return;
        }

        state = State.MINING;
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(mineTarget);
        String blockName = bs.getBlock().builtInRegistryHolder().key().identifier().getPath();
        boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
        announce("开始挖掘 " + blockName + " " + mineTarget.toShortString()
                + " (预计 " + (mineTotalTicks / 20) + " 秒"
                + (correctTool ? "" : " — 没有合适的工具！") + ")");
    }

    private void tickMine() {
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(mineTarget);

        // 方块消失了（被其他人挖掉，或者本来就是空气）
        if (bs.isAir()) {
            resetBlockCrack(level);
            announce("方块已经被挖掉了");
            completeAction();
            return;
        }

        // ── 第一步：走到方块旁边 ──────────────────────────────────────────────
        double dist = companion.position().distanceTo(
                net.minecraft.world.phys.Vec3.atCenterOf(mineTarget));
        if (dist > MINE_REACH) {
            resetBlockCrack(level);
            companion.getNavigation().moveTo(
                    mineTarget.getX() + 0.5, mineTarget.getY(),
                    mineTarget.getZ() + 0.5, MOVE_SPEED);
            return;  // 等待靠近
        }

        // ── 第二步：在范围内 — 开始挖掘！──────────────────────────────────
        // 面向方块
        companion.getLookControl().setLookAt(
                mineTarget.getX() + 0.5,
                mineTarget.getY() + 0.5,
                mineTarget.getZ() + 0.5);

        // 手臂挥动动画（模拟玩家）
        companion.swing(InteractionHand.MAIN_HAND);

        // 播放方块的敲击音效（每次挥动的"挖掘"声）
        net.minecraft.world.level.block.SoundType soundType = bs.getSoundType();
        level.playSound(null,
                mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5,
                soundType.getHitSound(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                soundType.getVolume() * 0.5F,  // 挖掘时音量较小
                soundType.getPitch());

        mineProgress++;

        // 更新裂纹动画：进度从 0（无裂纹）到 9（即将破碎）
        int crackStage = (int) ((double) mineProgress / mineTotalTicks * 9);
        crackStage = Math.max(0, Math.min(9, crackStage));
        level.destroyBlockProgress(companion.getId(), mineTarget, crackStage);

        if (mineProgress >= mineTotalTicks) {
            resetBlockCrack(level);

            // 破坏方块 — 只有使用正确工具时才掉落物品
            // （匹配原版行为：空手挖石头不掉落任何东西）
            boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
            boolean shouldDrop = correctTool || !requiresCorrectTool(bs);

            if (shouldDrop) {
                level.destroyBlock(mineTarget, true, companion, 32);
            } else {
                // 移除方块但不掉落物品（像玩家空手挖石头一样）
                level.destroyBlock(mineTarget, false, companion, 32);
            }

            // 播放方块破碎音效
            level.playSound(null,
                    mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5,
                    soundType.getBreakSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    soundType.getVolume(), soundType.getPitch());

            announce("挖完了！");
            completeAction();
        }
    }

    /** 清除方块裂纹动画（将进度设为 -1） */
    private void resetBlockCrack(ServerLevel level) {
        if (mineTarget != null) {
            level.destroyBlockProgress(companion.getId(), mineTarget, -1);
        }
    }

    /**
     * 自动从背包装备挖掘指定方块的最佳工具。
     * 如果当前手持物品已经够用，则不做任何操作。
     */
    private void autoEquipToolForBlock(BlockPos pos) {
        if (!(companion instanceof AICompanionEntity ace)) return;
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(pos);

        // 瞬间可挖掘的方块无需换工具
        float hardness = bs.getDestroySpeed(level, pos);
        if (hardness <= 0) return;

        // 如果当前手持工具已经合适，跳过
        ItemStack held = companion.getMainHandItem();
        if (isCorrectToolForBlock(held, bs) && getToolSpeed(held) >= 4.0F) return;

        // 在背包中搜索最佳工具
        int bestSlot = -1;
        float bestSpeed = getToolSpeed(held);

        for (int i = 0; i < ace.getInventory().getContainerSize(); i++) {
            ItemStack stack = ace.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String itemId = stack.getItem().builtInRegistryHolder()
                    .key().identifier().getPath();
            // 只考虑工具类物品（镐、斧、铲）
            if (itemId.contains("pickaxe") || itemId.contains("axe") ||
                itemId.contains("shovel")) {
                if (isCorrectToolForBlock(stack, bs)) {
                    float speed = getToolSpeed(stack);
                    if (speed > bestSpeed) {
                        bestSpeed = speed;
                        bestSlot = i;
                    }
                }
            }
        }

        if (bestSlot >= 0) {
            ItemStack tool = ace.getInventory().getItem(bestSlot);
            ItemStack oldHand = companion.getMainHandItem().copy();
            companion.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
            ace.getInventory().setItem(bestSlot, ItemStack.EMPTY);
            // 将原来手中的物品放回刚空出的格子
            if (!oldHand.isEmpty()) {
                ace.getInventory().setItem(bestSlot, oldHand);
            }
            String toolName = companion.getMainHandItem().getItem()
                    .builtInRegistryHolder().key().identifier().getPath();
            announce("装备了 " + toolName);
        }
    }

    /**
     * 计算挖掘所需 tick 数 — 匹配原版 Minecraft 公式。
     * <p>
     * 原版公式：
     * <ul>
     *   <li>使用正确工具：damagePerTick = toolSpeed / hardness / 30</li>
     *   <li>不使用正确工具：damagePerTick = 1 / hardness / 100</li>
     *   <li>所需时间 = ceil(1 / damagePerTick)</li>
     * </ul>
     * 工具速度：空手=1，木/金=2，石=4，铁=6，钻石=8，下界合金=9
     */
    private int calculateMineTicks(BlockPos pos) {
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(pos);
        float hardness = bs.getDestroySpeed(level, pos);
        if (hardness < 0) return Integer.MAX_VALUE;  // 不可破坏（基岩等）
        if (hardness == 0) return 5;                  // 瞬间可挖掘的方块（火把、花等）

        boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
        float ticks;
        if (correctTool) {
            float toolSpeed = getToolSpeed(companion.getMainHandItem());
            // 原版公式：时间 = hardness * 30 / toolSpeed
            ticks = hardness * 30.0F / toolSpeed;
        } else {
            // 原版空手挖掘不可收获方块：时间 = hardness * 100
            ticks = hardness * 100.0F;
        }
        return Math.max(5, (int) Math.ceil(ticks));
    }

    /**
     * 获取工具物品的挖掘速度。
     * 空手或非工具物品返回 1.0。
     */
    private float getToolSpeed(ItemStack held) {
        if (held.isEmpty()) return 1.0F;
        String itemId = held.getItem().builtInRegistryHolder()
                .key().identifier().getPath();

        // 根据物品 ID 中的等级名称匹配
        if (itemId.contains("netherite")) return 9.0F;
        if (itemId.contains("diamond"))   return 8.0F;
        if (itemId.contains("iron"))      return 6.0F;
        if (itemId.contains("stone"))     return 4.0F;
        if (itemId.contains("wooden") || itemId.contains("gold") || itemId.contains("golden"))
            return 2.0F;

        // 是工具但无法识别等级 — 按基础工具处理
        if (itemId.contains("pickaxe") || itemId.contains("axe") ||
            itemId.contains("shovel") || itemId.contains("hoe"))
            return 2.0F;

        return 1.0F;
    }

    /**
     * 检查方块是否需要正确工具才能掉落物品。
     * 大部分石头/矿石方块需要；泥土/木头等不需要。
     */
    private boolean requiresCorrectTool(BlockState bs) {
        String blockId = bs.getBlock().builtInRegistryHolder()
                .key().identifier().getPath();
        return blockId.contains("stone") || blockId.contains("ore") ||
               blockId.contains("cobble") || blockId.contains("deepslate") ||
               blockId.contains("netherrack") || blockId.contains("blackstone") ||
               blockId.contains("basalt") || blockId.contains("iron_block") ||
               blockId.contains("gold_block") || blockId.contains("diamond_block") ||
               blockId.contains("netherite") || blockId.contains("copper_block") ||
               blockId.contains("brick") || blockId.contains("furnace") ||
               blockId.contains("dispenser") || blockId.contains("dropper") ||
               blockId.contains("observer") || blockId.contains("hopper") ||
               blockId.contains("anvil") || blockId.contains("enchanting") ||
               blockId.contains("ender_chest") || blockId.contains("spawner");
    }

    /**
     * 检查手持物品是否是对应方块的"正确"工具类型。
     * 使用基于名称的启发式判断：对所有原版方块有效，对 mod 方块也基本合理。
     */
    private boolean isCorrectToolForBlock(ItemStack held, BlockState bs) {
        if (held.isEmpty()) return false;
        String itemId = held.getItem().builtInRegistryHolder()
                .key().identifier().getPath();
        String blockId = bs.getBlock().builtInRegistryHolder()
                .key().identifier().getPath();

        boolean isPickaxe = itemId.contains("pickaxe");
        boolean isAxe     = itemId.contains("axe") && !isPickaxe;
        boolean isShovel  = itemId.contains("shovel");
        boolean isSword   = itemId.contains("sword");
        boolean isHoe     = itemId.contains("hoe");

        // 石头 / 矿石 / 金属 → 镐
        if (blockId.contains("stone") || blockId.contains("ore") ||
            blockId.contains("cobble") || blockId.contains("deepslate") ||
            blockId.contains("netherrack") || blockId.contains("blackstone") ||
            blockId.contains("basalt") || blockId.contains("iron_block") ||
            blockId.contains("gold_block") || blockId.contains("diamond_block") ||
            blockId.contains("netherite") || blockId.contains("copper_block") ||
            blockId.contains("brick") || blockId.contains("furnace") ||
            blockId.contains("dispenser") || blockId.contains("dropper") ||
            blockId.contains("observer") || blockId.contains("hopper") ||
            blockId.contains("anvil") || blockId.contains("enchanting") ||
            blockId.contains("ender_chest") || blockId.contains("spawner")) {
            return isPickaxe;
        }

        // 木头 / 原木 / 木板 → 斧头
        if (blockId.contains("log") || blockId.contains("plank") ||
            blockId.contains("wood") || blockId.contains("oak") ||
            blockId.contains("birch") || blockId.contains("spruce") ||
            blockId.contains("jungle") || blockId.contains("acacia") ||
            blockId.contains("dark_oak") || blockId.contains("mangrove") ||
            blockId.contains("cherry") || blockId.contains("bamboo") ||
            blockId.contains("crafting_table") || blockId.contains("bookshelf") ||
            blockId.contains("chest") || blockId.contains("note_block")) {
            return isAxe;
        }

        // 泥土 / 沙子 / 沙砾 / 粘土 → 铲子
        if (blockId.contains("dirt") || blockId.contains("grass_block") ||
            blockId.contains("sand") || blockId.contains("gravel") ||
            blockId.contains("clay") || blockId.contains("soul_sand") ||
            blockId.contains("mycelium") || blockId.contains("podzol") ||
            blockId.contains("farmland") || blockId.contains("concrete_powder")) {
            return isShovel;
        }

        // 树叶 / 蜘蛛网 / 植物 → 剑或任何工具都可以
        if (blockId.contains("leaves") || blockId.contains("web") ||
            blockId.contains("vine") || blockId.contains("crop") ||
            blockId.contains("wheat") || blockId.contains("carrot") ||
            blockId.contains("potato") || blockId.contains("melon") ||
            blockId.contains("pumpkin")) {
            return isSword || isAxe || isHoe;
        }

        // 其他情况，任何工具都可以
        return isPickaxe || isAxe || isShovel || isSword || isHoe;
    }

    /**
     * 自动从背包装备最适合当前战斗场景的武器。
     * <ul>
     *   <li>目标较远 / 有高低差 / 无法到达 且背包有箭矢 → 优先使用远程武器（弓、弩）</li>
     *   <li>目标近距离可到达 / 箭矢耗尽 → 优先使用近战武器（剑、斧、三叉戟、锤）</li>
     * </ul>
     * 通过读取物品的 {@link ItemAttributeModifiers} 组件中的攻击伤害属性，
     * 选择同类武器中伤害最高的换到主手。
     */
    public void autoEquipWeapon(Entity target) {
        if (!(companion instanceof AICompanionEntity ace)) return;

        int arrows = countArrowsInInventory();
        boolean useRanged = target != null && shouldUseRanged(target);

        ItemStack held = companion.getMainHandItem();
        String heldId = held.isEmpty() ? ""
                : held.getItem().builtInRegistryHolder().key().identifier().getPath();
        boolean heldMatchesMode = useRanged ? isRangedWeapon(heldId) : isMeleeWeapon(heldId);

        AICompanion.LOGGER.debug("[Weapon] arrows={}, useRanged={}, held={}, heldMatchesMode={}",
                arrows, useRanged, heldId, heldMatchesMode);

        // 当前手持武器已符合战斗模式，跳过
        if (heldMatchesMode) return;

        float bestDamage = -1.0F;
        int bestSlot = -1;

        for (int i = 0; i < ace.getInventory().getContainerSize(); i++) {
            ItemStack stack = ace.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            String itemId = stack.getItem().builtInRegistryHolder()
                    .key().identifier().getPath();
            // 只考虑符合当前战斗模式的武器
            if (useRanged ? !isRangedWeapon(itemId) : !isMeleeWeapon(itemId)) continue;

            float dmg = getWeaponAttackDamage(stack);
            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestSlot = i;
            }
        }

        // 当前模式无合适武器时，回退到任意伤害武器
        if (bestSlot < 0) {
            for (int i = 0; i < ace.getInventory().getContainerSize(); i++) {
                ItemStack stack = ace.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                String itemId = stack.getItem().builtInRegistryHolder()
                        .key().identifier().getPath();
                if (!isDamageWeapon(itemId)) continue;
                float dmg = getWeaponAttackDamage(stack);
                if (dmg > bestDamage) {
                    bestDamage = dmg;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0) {
            ItemStack weapon = ace.getInventory().getItem(bestSlot);
            ItemStack oldHand = companion.getMainHandItem().copy();
            companion.setItemInHand(InteractionHand.MAIN_HAND, weapon.copy());
            ace.getInventory().setItem(bestSlot, ItemStack.EMPTY);
            if (!oldHand.isEmpty()) {
                ace.getInventory().setItem(bestSlot, oldHand);
            }
            String weaponId = companion.getMainHandItem().getItem()
                    .builtInRegistryHolder().key().identifier().getPath();
            // 只在武器真正更换时播报，避免每 tick 重复刷屏
            if (!weaponId.equals(lastAnnouncedWeaponId)) {
                lastAnnouncedWeaponId = weaponId;
                announce("装备了 " + weaponId);
            }
        } else {
            AICompanion.LOGGER.debug("[Weapon] 背包中没有合适的 {} 武器",
                    useRanged ? "远程" : "近战");
        }
    }

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
        if (!(companion instanceof AICompanionEntity ace)) return 0;
        int count = 0;
        for (int i = 0; i < ace.getInventory().getContainerSize(); i++) {
            ItemStack stack = ace.getInventory().getItem(i);
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
     * 根据物品 ID 判断是否为伤害类武器（近战或远程）。
     */
    private boolean isDamageWeapon(String itemId) {
        return isMeleeWeapon(itemId) || isRangedWeapon(itemId);
    }

    /**
     * 判断是否为远程武器（弓、弩）。
     */
    private boolean isRangedWeapon(String itemId) {
        return itemId.contains("bow") || itemId.contains("crossbow");
    }

    /**
     * 判断是否为近战武器（剑、斧、三叉戟、锤）。
     */
    private boolean isMeleeWeapon(String itemId) {
        return itemId.contains("sword")
                || itemId.contains("axe")
                || itemId.contains("trident")
                || itemId.contains("mace");
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

    // ── 攻击 ────────────────────────────────────────────────────────────────

    private void beginAttack(Action action) {
        String targetName = action.getTargetEntityName();
        if (targetName == null || targetName.isBlank()) {
            AICompanion.LOGGER.warn("[AI] ATTACK action missing targetName");
            completeAction();
            return;
        }
        attackTarget = findEntityByName(targetName);
        if (attackTarget == null) {
            announce("没有找到 " + targetName);
            completeAction();
            return;
        }
        // 攻击前自动从背包中装备最佳武器（根据距离决定近战/远程）
        autoEquipWeapon(attackTarget);

        attackCooldown = 0;
        state = State.ATTACKING;
        // 同一目标只播报一次"发现/发起攻击"
        if (!targetName.equals(lastAnnouncedTargetId)) {
            lastAnnouncedTargetId = targetName;
            announce("发现 " + targetName + "，发起攻击！");
        }
    }

    private void tickAttack() {
        if (attackTarget == null || attackTarget.isRemoved()) {
            announce("目标消失了");
            completeAction();
            return;
        }

        ServerLevel level = (ServerLevel) companion.level();
        double dist = companion.distanceTo(attackTarget);

        // 每 tick 更新武器选择（距离变化时可能需要在远程/近战之间切换）
        autoEquipWeapon(attackTarget);

        // 判断是否正在使用远程武器（同时确认背包有箭，防止无箭时卡在弓蓄力分支）
        String heldId = companion.getMainHandItem().isEmpty() ? ""
                : companion.getMainHandItem().getItem().builtInRegistryHolder()
                        .key().identifier().getPath();
        boolean isRanged = isRangedWeapon(heldId) && countArrowsInInventory() > 0;

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
                return;  // 继续蓄力，等待
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
            completeAction();
        }
    }

    /**
     * 发射箭矢。从背包或主手中消耗一支箭，生成箭矢实体并射向目标。
     *
     * @param power 蓄力强度（0.1 到 1.0），与原版弓蓄力公式一致：
     *              影响箭矢飞行速度和伤害（power² × 伤害系数）。
     */
    private void fireArrow(ServerLevel level, float power) {
        // 从背包或主手中消耗一支箭
        ItemStack arrowStack = ItemStack.EMPTY;
        if (companion instanceof AICompanionEntity ace) {
            // 先查背包
            for (int i = 0; i < ace.getInventory().getContainerSize(); i++) {
                ItemStack stack = ace.getInventory().getItem(i);
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

        float speed = BOW_ATTACK_SPEED * power;  // 箭矢初速（满弦 = 1.6）
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
                net.minecraft.sounds.SoundEvents.ARROW_SHOOT,
                net.minecraft.sounds.SoundSource.HOSTILE,
                1.0F, 1.0F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    /**
     * 检查同伴是否能看到目标（视线无方块阻挡）。
     */
    private boolean hasLineOfSight(Entity target) {
        return companion.hasLineOfSight(target);
    }

    // ── 即时动作 ────────────────────────────────────────────────────────────

    private void executeChat(Action action) {
        String msg = action.getMessage();
        if (msg != null && !msg.isBlank()) {
            broadcast(msg);
        }
        completeAction();
    }

    private void executeEat() {
        AICompanionEntity ace = (AICompanionEntity) companion;
        if (ace.getHunger() >= ace.getMaxHunger()) {
            announce("我不饿，不需要吃东西");
            completeAction();
            return;
        }
        // 尝试从背包中找食物
        if (ace.tryEat()) {
            companion.swing(InteractionHand.MAIN_HAND);
            announce("吃了点东西，饱食度恢复到 " + ace.getHunger());
        } else {
            announce("背包里没有食物可以吃");
        }
        completeAction();
    }

    private void executeSleep() {
        AICompanionEntity ace = (AICompanionEntity) companion;
        if (ace.isCompanionSleeping()) {
            announce("已经在休息了 zzZ");
            completeAction();
            return;
        }
        ace.setCompanionSleeping();
        announce("休息一下 zzZ");
        completeAction();
    }

    private void executeWakeUp() {
        AICompanionEntity ace = (AICompanionEntity) companion;
        if (ace.isCompanionSleeping()) {
            ace.wakeCompanionUp();
            announce("醒来了！");
        } else {
            ace.wakeCompanionUp();  // 同时重置姿态
            announce("已经是清醒状态");
        }
        completeAction();
    }

    private void executeDropItem() {
        ItemStack stack = companion.getMainHandItem();
        if (stack.isEmpty()) {
            announce("手里没东西可丢");
        } else {
            ServerLevel level = (ServerLevel) companion.level();
            companion.spawnAtLocation(level, stack.copy());
            stack.setCount(0);
            announce("扔掉了手里的东西");
        }
        completeAction();
    }

    private void executeUseItem() {
        // 占位实现：只挥动胳膊。真正的物品使用逻辑取决于物品类型
        ItemStack stack = companion.getMainHandItem();
        if (stack.isEmpty()) {
            announce("手里没有物品");
        } else {
            companion.swing(InteractionHand.MAIN_HAND);
            announce("使用了 " + stack.getHoverName().getString());
        }
        completeAction();
    }

    private void executePlace(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] PLACE_BLOCK missing targetPos");
            completeAction();
            return;
        }
        ServerLevel level = (ServerLevel) companion.level();
        BlockPos pos = action.getTargetPos();
        if (!level.getBlockState(pos).isAir()) {
            announce("那个位置已经有方块了");
            completeAction();
            return;
        }
        // 检查手中是否有方块，否则尝试从背包中找
        ItemStack held = companion.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            // 尝试在背包中找到方块物品并装备
            if (companion instanceof AICompanionEntity ace) {
                for (int i = 0; i < ace.getInventory().getContainerSize(); i++) {
                    ItemStack stack = ace.getInventory().getItem(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                        // 装备到主手
                        ItemStack oldHand = companion.getMainHandItem().copy();
                        companion.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
                        stack.setCount(0);
                        ace.getInventory().setItem(i, ItemStack.EMPTY);
                        // 将原来的手持物品放回背包
                        if (!oldHand.isEmpty()) {
                            addToInventory(oldHand);
                        }
                        held = companion.getMainHandItem();
                        break;
                    }
                }
            }
        }
        if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            announce("背包里没有方块可以放置");
            completeAction();
            return;
        }
        net.minecraft.world.item.BlockItem blockItem =
                (net.minecraft.world.item.BlockItem) held.getItem();
        if (level.setBlock(pos, blockItem.getBlock().defaultBlockState(), 3)) {
            held.shrink(1);
            companion.swing(InteractionHand.MAIN_HAND);
            announce("放置了方块");
        } else {
            announce("放置失败");
        }
        completeAction();
    }

    /**
     * 尝试将 ItemStack 添加到同伴的背包中。
     * 返回未能放入的物品。
     */
    private ItemStack addToInventory(ItemStack stack) {
        if (!(companion instanceof AICompanionEntity ace)) return stack;
        var inv = ace.getInventory();
        int remaining = stack.getCount();
        // 先尝试与已有物品堆叠
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int maxAdd = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                if (maxAdd > 0) {
                    slot.grow(maxAdd);
                    inv.setItem(i, slot);
                    remaining -= maxAdd;
                }
            }
        }
        // 然后尝试空格子
        if (remaining > 0) {
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack slot = inv.getItem(i);
                if (slot.isEmpty()) {
                    ItemStack toAdd = stack.copy();
                    toAdd.setCount(Math.min(remaining, stack.getMaxStackSize()));
                    inv.setItem(i, toAdd);
                    remaining -= toAdd.getCount();
                }
            }
        }
        if (remaining <= 0) return ItemStack.EMPTY;
        ItemStack result = stack.copy();
        result.setCount(remaining);
        return result;
    }

    private void beginWait() {
        state = State.WAITING;
        waitRemaining = DEFAULT_WAIT;
    }

    private void tickWait() {
        if (--waitRemaining <= 0) completeAction();
    }

    // ── 辅助方法 ────────────────────────────────────────────────────────────

    private void completeAction() {
        Action done = currentAction;
        currentAction = null;
        state = State.IDLE;
        AICompanion.LOGGER.debug("[AI] 动作完成: {}", done);
    }

    private void cleanupCurrentAction() {
        if (state == State.MOVING) companion.getNavigation().stop();
        if (state == State.WAITING) waitRemaining = 0;
        // 如果正在挖掘，清除方块裂纹动画
        if (state == State.MINING && !companion.level().isClientSide()) {
            resetBlockCrack((ServerLevel) companion.level());
        }
        // 如果同伴在睡觉，唤醒它以便姿态正确重置
        if (companion instanceof AICompanionEntity ace && ace.isCompanionSleeping()) {
            ace.wakeCompanionUp();
        }
        // 如果正在蓄力弓箭，取消蓄力（防止弓箭卡在使用状态）
        if (bowCharging) {
            companion.stopUsingItem();
            bowCharging = false;
        }
        // 战斗结束，重置武器模式锁和播报去重，以便下次战斗重新评估
        if (state == State.ATTACKING) {
            lastRangedMode = null;
            modeLockRemaining = 0;
            lastAnnouncedWeaponId = "";
            lastAnnouncedTargetId = "";
        }
    }

    /** 查找类型 ID 包含 {@code name} 的最近实体（不区分大小写） */
    private Entity findEntityByName(String name) {
        ServerLevel level = (ServerLevel) companion.level();
        AABB box = companion.getBoundingBox().inflate(ENTITY_SCAN_RANGE);
        Predicate<Entity> predicate = e -> true;
        List<Entity> entities = level.getEntities((Entity) null, box, predicate);

        Entity best    = null;
        double bestDst = Double.MAX_VALUE;
        String lower   = name.toLowerCase();

        for (Entity e : entities) {
            if (e == companion) continue;
            String id   = e.getType().builtInRegistryHolder().key().identifier().toString();
            String disp = e.getName().getString();
            if (id.toLowerCase().contains(lower) || disp.toLowerCase().contains(lower)) {
                double d = companion.distanceToSqr(e);
                if (d < bestDst) {
                    bestDst = d;
                    best    = e;
                }
            }
        }
        return best;
    }

    /** 向 32 格内的所有玩家发送聊天消息，并记录到聊天历史中 */
    private void broadcast(String message) {
        ServerLevel level = (ServerLevel) companion.level();
        String senderName = companion.getName().getString();
        Component text = Component.literal("[" + senderName + "] " + message);
        AABB area = companion.getBoundingBox().inflate(32);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, area)) {
            player.sendSystemMessage(text);
        }
        // 记录到聊天历史中，以便 AI 大脑能看到说过的内容
        if (chatHistory != null) {
            chatHistory.add(senderName, message);
        }
    }

    private void announce(String message) {
        AICompanion.LOGGER.debug("[AI] {}", message);
        broadcast(message);
    }
}
