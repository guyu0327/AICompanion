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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * Executes AI-decided actions inside the game world.
 * <p>
 * Usage: call {@link #tick()} once per server tick from {@code AICompanionEntity.tick()}.
 * Call {@link #startAction(Action)} when the AI brain returns a new decision.
 */
public class ActionExecutor {

    // ── Execution state ─────────────────────────────────────────────────────

    public enum State {
        IDLE,
        MOVING,
        MINING,
        ATTACKING,
        WAITING
    }

    // ── Tuning constants ────────────────────────────────────────────────────

    private static final double MOVE_SPEED        = 1.0;
    private static final double ARRIVAL_THRESHOLD = 2.0;   // blocks — when MOVE counts as done
    private static final double MINE_REACH        = 2.5;   // must be this close to start mining
    private static final double ATTACK_REACH      = 3.0;
    private static final float  ATTACK_DAMAGE     = 4.0F;
    private static final int    ATTACK_COOLDOWN   = 14;    // ticks between swings (~0.7 s)
    private static final int    DEFAULT_WAIT      = 40;    // ticks (~2 s)
    private static final int    ENTITY_SCAN_RANGE = 24;

    // ── Fields ──────────────────────────────────────────────────────────────

    private final net.minecraft.world.entity.Mob companion;
    private final ChatHistory chatHistory;

    private State  state         = State.IDLE;
    private Action currentAction = null;

    // MOVE
    private BlockPos moveTarget;

    // MINE
    private BlockPos mineTarget;
    private int      mineProgress;
    private int      mineTotalTicks;

    // ATTACK
    private Entity attackTarget;
    private int    attackCooldown;

    // WAIT
    private int waitRemaining;

    // Latest chat messages (set externally by ChatHandler / AIService)
    public String lastPlayerMessage;
    public String lastChatMessage;

    // ── Constructor ─────────────────────────────────────────────────────────

    public ActionExecutor(net.minecraft.world.entity.Mob companion, ChatHistory chatHistory) {
        this.companion = companion;
        this.chatHistory = chatHistory;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Begin executing a new action.  Cancels whatever was running before.
     */
    public void startAction(Action action) {
        if (action == null || action.getType() == null) return;
        AICompanion.LOGGER.info("[AI] 开始执行: {}", action);
        cleanupCurrentAction();
        currentAction = action;
        beginAction(action);
    }

    /** Stop whatever is currently running and go IDLE. */
    public void cancel() {
        cleanupCurrentAction();
        currentAction = null;
        state = State.IDLE;
    }

    /** True when no action is in progress. */
    public boolean isIdle() {
        return state == State.IDLE;
    }

    public State getState()          { return state; }
    public Action getCurrentAction() { return currentAction; }

    /**
     * Drive the state machine.  Call once per server tick.
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

    // ── Begin actions ───────────────────────────────────────────────────────

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

    // ── MOVE ────────────────────────────────────────────────────────────────

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

        // Path lost or stuck
        if (companion.getNavigation().isDone()) {
            double dist = companion.position().distanceTo(
                    net.minecraft.world.phys.Vec3.atCenterOf(moveTarget));
            if (dist <= ARRIVAL_THRESHOLD) {
                announce("已到达 " + moveTarget.toShortString());
                completeAction();
            } else {
                // Try to re-path
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

    // ── MINE ────────────────────────────────────────────────────────────────

    private void beginMine(Action action) {
        if (action.getTargetPos() == null) {
            AICompanion.LOGGER.warn("[AI] MINE action missing target position");
            completeAction();
            return;
        }
        mineTarget     = action.getTargetPos();
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

        // Block disappeared (someone else broke it, or it was air)
        if (bs.isAir()) {
            resetBlockCrack(level);
            announce("方块已经被挖掉了");
            completeAction();
            return;
        }

        // ── Step 1: Walk to the block ───────────────────────────────────────
        double dist = companion.position().distanceTo(
                net.minecraft.world.phys.Vec3.atCenterOf(mineTarget));
        if (dist > MINE_REACH) {
            resetBlockCrack(level);
            companion.getNavigation().moveTo(
                    mineTarget.getX() + 0.5, mineTarget.getY(),
                    mineTarget.getZ() + 0.5, MOVE_SPEED);
            return;  // wait until closer
        }

        // ── Step 2: In range — mine! ────────────────────────────────────────
        // Face the block
        companion.getLookControl().setLookAt(
                mineTarget.getX() + 0.5,
                mineTarget.getY() + 0.5,
                mineTarget.getZ() + 0.5);

        // Arm swing animation (player-like)
        companion.swing(InteractionHand.MAIN_HAND);

        // Play the block's hit sound (the "dig" sound per swing)
        net.minecraft.world.level.block.SoundType soundType = bs.getSoundType();
        level.playSound(null,
                mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5,
                soundType.getHitSound(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                soundType.getVolume() * 0.5F,  // quieter during mining
                soundType.getPitch());

        mineProgress++;

        // Update crack animation: progress goes from 0 (no crack) to 9 (about to break)
        int crackStage = (int) ((double) mineProgress / mineTotalTicks * 9);
        crackStage = Math.max(0, Math.min(9, crackStage));
        level.destroyBlockProgress(companion.getId(), mineTarget, crackStage);

        if (mineProgress >= mineTotalTicks) {
            resetBlockCrack(level);

            // Break the block — only drop items if we had the correct tool
            // (matches vanilla behavior: stone mined by hand drops nothing)
            boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
            boolean shouldDrop = correctTool || !requiresCorrectTool(bs);

            if (shouldDrop) {
                level.destroyBlock(mineTarget, true, companion, 32);
            } else {
                // Remove block without drops (like a player mining stone with bare hands)
                level.destroyBlock(mineTarget, false, companion, 32);
            }

            // Play the block's break sound
            level.playSound(null,
                    mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5,
                    soundType.getBreakSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    soundType.getVolume(), soundType.getPitch());

            announce("挖完了！");
            completeAction();
        }
    }

    /** Clear the block crack animation (set progress to -1). */
    private void resetBlockCrack(ServerLevel level) {
        if (mineTarget != null) {
            level.destroyBlockProgress(companion.getId(), mineTarget, -1);
        }
    }

    /**
     * Calculate mining time in ticks — matches vanilla Minecraft formula.
     * <p>
     * Vanilla formula:
     * <ul>
     *   <li>With correct tool: damagePerTick = toolSpeed / hardness / 30</li>
     *   <li>Without correct tool: damagePerTick = 1 / hardness / 100</li>
     *   <li>Time = ceil(1 / damagePerTick)</li>
     * </ul>
     * Tool speeds: bare hands=1, wood/gold=2, stone=4, iron=6, diamond=8, netherite=9
     */
    private int calculateMineTicks(BlockPos pos) {
        ServerLevel level = (ServerLevel) companion.level();
        BlockState bs = level.getBlockState(pos);
        float hardness = bs.getDestroySpeed(level, pos);
        if (hardness < 0) return Integer.MAX_VALUE;  // unbreakable (bedrock etc.)
        if (hardness == 0) return 5;                  // instant-mine blocks (torch, flower, etc.)

        boolean correctTool = isCorrectToolForBlock(companion.getMainHandItem(), bs);
        float ticks;
        if (correctTool) {
            float toolSpeed = getToolSpeed(companion.getMainHandItem());
            // Vanilla: time = hardness * 30 / toolSpeed
            ticks = hardness * 30.0F / toolSpeed;
        } else {
            // Vanilla bare-hands on unharvestable block: time = hardness * 100
            ticks = hardness * 100.0F;
        }
        return Math.max(5, (int) Math.ceil(ticks));
    }

    /**
     * Get the mining speed of a tool item.
     * Returns 1.0 for bare hands or non-tool items.
     */
    private float getToolSpeed(ItemStack held) {
        if (held.isEmpty()) return 1.0F;
        String itemId = held.getItem().builtInRegistryHolder()
                .key().identifier().getPath();

        // Match by tier name in the item ID
        if (itemId.contains("netherite")) return 9.0F;
        if (itemId.contains("diamond"))   return 8.0F;
        if (itemId.contains("iron"))      return 6.0F;
        if (itemId.contains("stone"))     return 4.0F;
        if (itemId.contains("wooden") || itemId.contains("gold") || itemId.contains("golden"))
            return 2.0F;

        // It's a tool but we don't recognize the tier — assume basic
        if (itemId.contains("pickaxe") || itemId.contains("axe") ||
            itemId.contains("shovel") || itemId.contains("hoe"))
            return 2.0F;

        return 1.0F;
    }

    /**
     * Check if a block requires the correct tool for drops.
     * Most stone/ore blocks do; dirt/wood/etc. don't.
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
     * Check if the held item is the "right" tool type for a block.
     * Uses name-based heuristic: works for all vanilla blocks, reasonable for modded ones.
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

        // Stone / ores / metal → pickaxe
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

        // Wood / logs / planks → axe
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

        // Dirt / sand / gravel / clay → shovel
        if (blockId.contains("dirt") || blockId.contains("grass_block") ||
            blockId.contains("sand") || blockId.contains("gravel") ||
            blockId.contains("clay") || blockId.contains("soul_sand") ||
            blockId.contains("mycelium") || blockId.contains("podzol") ||
            blockId.contains("farmland") || blockId.contains("concrete_powder")) {
            return isShovel;
        }

        // Leaves / web / plants → sword or anything works
        if (blockId.contains("leaves") || blockId.contains("web") ||
            blockId.contains("vine") || blockId.contains("crop") ||
            blockId.contains("wheat") || blockId.contains("carrot") ||
            blockId.contains("potato") || blockId.contains("melon") ||
            blockId.contains("pumpkin")) {
            return isSword || isAxe || isHoe;
        }

        // For anything else, any tool is fine
        return isPickaxe || isAxe || isShovel || isSword || isHoe;
    }

    // ── ATTACK ──────────────────────────────────────────────────────────────

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
        attackCooldown = 0;
        state = State.ATTACKING;
        announce("发现 " + targetName + "，发起攻击！");
    }

    private void tickAttack() {
        if (attackTarget == null || attackTarget.isRemoved()) {
            announce("目标消失了");
            completeAction();
            return;
        }

        ServerLevel level = (ServerLevel) companion.level();
        double dist = companion.distanceTo(attackTarget);

        // Chase
        if (dist > ATTACK_REACH) {
            companion.getNavigation().moveTo(attackTarget, MOVE_SPEED);
            return;
        }

        // Face the target
        companion.getLookControl().setLookAt(attackTarget, 30F, 30F);

        // Cooldown between swings
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }

        // Strike!
        boolean hit = attackTarget.hurtServer(
                level,
                level.damageSources().mobAttack(companion),
                ATTACK_DAMAGE);
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
            announce("目标已被击败！");
            completeAction();
        }
    }

    // ── Instant actions ─────────────────────────────────────────────────────

    private void executeChat(Action action) {
        String msg = action.getMessage();
        if (msg != null && !msg.isBlank()) {
            broadcast(msg);
        }
        completeAction();
    }

    private void executeEat() {
        // NOTE: Mob does not have a FoodData; full hunger system arrives in Phase 5.
        // For now, this action is a placeholder that just plays the eating animation.
        ItemStack stack = companion.getMainHandItem();
        if (stack.isEmpty()) {
            announce("手里没有食物");
        } else {
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) {
                announce("这个不能吃");
            } else {
                companion.swing(InteractionHand.MAIN_HAND);
                announce("吃了一口（饥饿系统尚未实装）");
            }
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
            ace.wakeCompanionUp();  // also resets pose
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
        // Placeholder: just swing the arm.  Real item-use logic depends on
        // the item type and is deferred to Phase 5.
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
        // Simplified: place a cobblestone if the companion has one.
        // A full implementation would look at the AI's inventory.
        ItemStack held = companion.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            announce("手里没有方块可以放置");
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

    private void beginWait() {
        state = State.WAITING;
        waitRemaining = DEFAULT_WAIT;
    }

    private void tickWait() {
        if (--waitRemaining <= 0) completeAction();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void completeAction() {
        Action done = currentAction;
        currentAction = null;
        state = State.IDLE;
        AICompanion.LOGGER.info("[AI] 动作完成: {}", done);
    }

    private void cleanupCurrentAction() {
        if (state == State.MOVING) companion.getNavigation().stop();
        if (state == State.WAITING) waitRemaining = 0;
        // Clear block crack animation if we were mining
        if (state == State.MINING && !companion.level().isClientSide()) {
            resetBlockCrack((ServerLevel) companion.level());
        }
        // Wake the companion if it was sleeping so the pose resets cleanly
        if (companion instanceof AICompanionEntity ace && ace.isCompanionSleeping()) {
            ace.wakeCompanionUp();
        }
    }

    /** Find the nearest entity whose type-id contains {@code name} (case-insensitive). */
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

    /** Send a chat message to all players within 32 blocks and record it in chat history. */
    private void broadcast(String message) {
        ServerLevel level = (ServerLevel) companion.level();
        String senderName = companion.getName().getString();
        Component text = Component.literal("[" + senderName + "] " + message);
        AABB area = companion.getBoundingBox().inflate(32);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, area)) {
            player.sendSystemMessage(text);
        }
        // Record in chat history so the AI brain can see what was said
        if (chatHistory != null) {
            chatHistory.add(senderName, message);
        }
    }

    private void announce(String message) {
        AICompanion.LOGGER.info("[AI] {}", message);
        broadcast(message);
    }
}
