package com.guyu.aicompanion.action;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * AI 动作执行器 — 状态机调度中心。
 * <p>
 * 负责管理动作状态（IDLE / MOVING / MINING / ATTACKING / WAITING），
 * 并将具体逻辑委托给各功能控制器：
 * <ul>
 *   <li>{@link MovementController} — 移动</li>
 *   <li>{@link MiningController} — 挖掘</li>
 *   <li>{@link CombatController} — 战斗</li>
 *   <li>{@link ItemController} — 聊天 / 进食 / 睡觉 / 放置 等即时动作</li>
 * </ul>
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

    private static final int DEFAULT_WAIT = 40; // tick 数（约 2 秒）

    // ── 字段 ────────────────────────────────────────────────────────────────

    private final AICompanionEntity companion;
    private final ChatHistory chatHistory;

    private State state = State.IDLE;
    private Action currentAction = null;

    // 等待相关
    private int waitRemaining;

    // 功能控制器
    private final MovementController movementController;
    private final MiningController miningController;
    private final CombatController combatController;
    private final ItemController itemController;

    // 最近的聊天消息（由 ChatHandler / AIService 外部设置）
    public String lastPlayerMessage;
    public String lastChatMessage;

    // ── 构造函数 ────────────────────────────────────────────────────────────

    public ActionExecutor(AICompanionEntity companion, ChatHistory chatHistory) {
        this.companion = companion;
        this.chatHistory = chatHistory;
        this.movementController = new MovementController(companion, chatHistory, this);
        this.miningController = new MiningController(companion, chatHistory, this);
        this.combatController = new CombatController(companion, chatHistory, this);
        this.itemController = new ItemController(companion, chatHistory, this);
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

    public State getState() {
        return state;
    }

    public Action getCurrentAction() {
        return currentAction;
    }

    /**
     * 委托给战斗控制器 — 自动装备最佳武器。
     * 由 {@code AICompanionEntity.tick()} 每 tick 调用。
     */
    public void autoEquipWeapon(Entity target) {
        combatController.autoEquipWeapon(target);
    }

    /**
     * 委托给战斗控制器 — 自动装备最佳盔甲。
     * 由 {@code AICompanionEntity.tick()} 定期调用（有节流）。
     */
    public void autoEquipArmor() {
        combatController.autoEquipArmor();
    }

    /**
     * 驱动状态机。每个服务器 tick 调用一次。
     */
    public void tick() {
        if (companion.level().isClientSide()) return;

        // 战斗冷却始终递减（即使 IDLE 状态也要递减，以便冷却结束后恢复战斗）
        combatController.tickCooldown();

        if (state == State.IDLE) return;

        switch (state) {
            case MOVING    -> movementController.tickMove();
            case MINING    -> miningController.tickMine();
            case ATTACKING -> combatController.tickAttack();
            case WAITING   -> tickWait();
            default        -> {}
        }
    }

    /**
     * 是否正在撤退冷却中（血量过低后短时间内不再主动攻击）。
     * 由 {@code AICompanionEntity.tick()} 检查以决定是否启动新攻击。
     */
    public boolean isRetreating() {
        return combatController.isRetreating();
    }

    // ── 开始执行动作（分发到各控制器）───────────────────────────────────────

    private void beginAction(Action action) {
        switch (action.getType()) {
            case MOVE        -> movementController.beginMove(action);
            case MINE        -> miningController.beginMine(action);
            case ATTACK      -> combatController.beginAttack(action);
            case CHAT        -> itemController.executeChat(action);
            case EAT         -> itemController.executeEat();
            case SLEEP       -> itemController.executeSleep();
            case WAKE_UP     -> itemController.executeWakeUp();
            case DROP_ITEM   -> itemController.executeDropItem();
            case USE_ITEM    -> itemController.executeUseItem();
            case PLACE_BLOCK -> itemController.executePlace(action);
            case WAIT        -> beginWait();
        }
    }

    // ── 等待 ────────────────────────────────────────────────────────────────

    private void beginWait() {
        state = State.WAITING;
        waitRemaining = DEFAULT_WAIT;
    }

    private void tickWait() {
        if (--waitRemaining <= 0) completeAction();
    }

    // ── 包级私有 API — 供控制器回调 ──────────────────────────────────────────

    /** 设置执行状态（控制器在完成初始化或状态转换时调用） */
    void setState(State newState) {
        this.state = newState;
    }

    /**
     * 完成当前动作，回到 IDLE 状态。
     * 控制器在动作完成或失败时调用。
     */
    void completeAction() {
        Action done = currentAction;
        currentAction = null;
        state = State.IDLE;
        AICompanion.LOGGER.debug("[AI] 动作完成: {}", done);
    }

    /**
     * 清理当前动作的副作用（导航、裂纹动画、蓄力、睡眠等）。
     * 在开始新动作或取消时调用。
     */
    private void cleanupCurrentAction() {
        if (state == State.MOVING) movementController.cleanup();
        if (state == State.WAITING) waitRemaining = 0;
        if (state == State.MINING) miningController.cleanup();
        if (state == State.ATTACKING) combatController.cleanup();
        // 如果同伴在睡觉，唤醒它以便姿态正确重置
        if (companion.isCompanionSleeping()) {
            companion.wakeCompanionUp();
        }
    }

    /** 向 32 格内的所有玩家发送聊天消息，并记录到聊天历史中 */
    void broadcast(String message) {
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

    /** 同时记录到日志并广播给附近玩家 */
    void announce(String message) {
        AICompanion.LOGGER.debug("[AI] {}", message);
        broadcast(message);
    }
}
