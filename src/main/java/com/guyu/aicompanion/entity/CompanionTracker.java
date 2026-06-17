package com.guyu.aicompanion.entity;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务器级别的同伴状态追踪器（静态单例，服务器重启后重置）。
 * <p>
 * 负责：
 * <ul>
 *   <li>追踪同伴是否存活 / 是否在重生冷却中</li>
 *   <li>保存死亡同伴的背包、模式、饥饿值，用于重生恢复</li>
 *   <li>驱动重生倒计时（每 tick 递减，到 0 时在拥有者附近重生）</li>
 * </ul>
 */
public class CompanionTracker {

    /** 重生冷却 tick 数（10 秒 × 20 tick/秒） */
    private static final int RESPAWN_COOLDOWN_TICKS = 200;

    private static CompanionTracker instance = new CompanionTracker();

    /** 同伴拥有者的 UUID */
    private @Nullable UUID ownerUuid;

    /** 重生倒计时（-1 = 不在重生中，>0 = 倒计时中） */
    private int respawnCountdown = -1;

    // ── 死亡时保存的同伴数据（用于重生恢复）──────────────────────────────────
    private final List<ItemStack> savedInventory = new ArrayList<>();
    private ItemStack savedMainHand = ItemStack.EMPTY;
    private CompanionMode savedMode = CompanionMode.FOLLOW;
    private int savedHunger = 20;

    private CompanionTracker() {}

    /** 获取全局追踪器实例 */
    public static CompanionTracker get() {
        return instance;
    }

    /** 重置追踪器（服务器启动时调用） */
    public static void reset() {
        instance = new CompanionTracker();
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    public @Nullable UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(@Nullable UUID uuid) {
        this.ownerUuid = uuid;
    }

    /** 同伴是否正在重生冷却中 */
    public boolean isRespawning() {
        return respawnCountdown >= 0;
    }

    /** 重生剩余秒数（用于向玩家显示） */
    public int getRespawnSecondsRemaining() {
        return respawnCountdown > 0 ? (respawnCountdown + 19) / 20 : 0;
    }

    // ── 死亡处理 ─────────────────────────────────────────────────────────────

    /**
     * 同伴死亡时调用：保存状态并开始重生倒计时。
     * <p>
     * 如果配置为死亡掉落，则不保存背包和手持物品（物品已掉落在地上）。
     */
    public void onCompanionDied(AICompanionEntity companion) {
        this.ownerUuid = companion.getOwnerUuid();
        this.savedMode = companion.getMode();

        boolean dropItems = Config.DROP_ITEMS_ON_DEATH.get();

        if (!dropItems) {
            // 不掉落：保存背包和主手物品，重生后恢复
            this.savedHunger = companion.getHunger();
            savedInventory.clear();
            for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
                ItemStack stack = companion.getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    savedInventory.add(stack.copy());
                }
            }
            savedMainHand = companion.getMainHandItem().copy();
        } else {
            // 掉落：清空存档，重生后背包为空
            this.savedHunger = 20; // 重生后饥饿值回满
            savedInventory.clear();
            savedMainHand = ItemStack.EMPTY;
        }

        // 开始重生倒计时
        respawnCountdown = RESPAWN_COOLDOWN_TICKS;
        AICompanion.LOGGER.info("[Companion] 同伴死亡，{} 秒后重生",
                RESPAWN_COOLDOWN_TICKS / 20);
    }

    // ── 每 tick 驱动 ─────────────────────────────────────────────────────────

    /**
     * 每个服务器 tick 调用。递减重生倒计时，到 0 时执行重生。
     */
    public void tick(MinecraftServer server) {
        if (respawnCountdown > 0) {
            respawnCountdown--;

            // 在特定时间点通知拥有者
            if (respawnCountdown == 100) { // 5 秒
                notifyOwner(server, "同伴将在 5 秒后重生...");
            } else if (respawnCountdown == 40) { // 8 秒（即剩余 2 秒）
                notifyOwner(server, "同伴即将重生！");
            }

            if (respawnCountdown == 0) {
                // 倒计时结束，执行重生
                doRespawn(server);
            }
        }
    }

    /** 在拥有者附近重生同伴 */
    private void doRespawn(MinecraftServer server) {
        if (ownerUuid == null) {
            AICompanion.LOGGER.warn("[Companion] 重生失败：没有拥有者");
            return;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            AICompanion.LOGGER.info("[Companion] 拥有者不在线，延迟重生");
            // 拥有者不在线，保持倒计时为 0 状态，等下次 tick 或玩家上线时重生
            respawnCountdown = 0;
            return;
        }

        AICompanionEntity companion = CompanionSpawner.spawnNearPlayer(owner);
        if (companion != null) {
            // 恢复保存的状态
            restoreState(companion);
            notifyOwner(server, "同伴已重生！");
            AICompanion.LOGGER.info("[Companion] 同伴已在 {} 附近重生",
                    owner.getName().getString());
        } else {
            AICompanion.LOGGER.error("[Companion] 重生失败：无法生成实体");
        }

        // 清除保存的数据
        clearSavedData();
    }

    /**
     * 尝试重生（玩家上线时调用）。如果倒计时已结束但还未重生，立即执行。
     */
    public void tryRespawnIfReady(MinecraftServer server) {
        if (respawnCountdown == 0 && ownerUuid != null) {
            doRespawn(server);
        }
    }

    private void restoreState(AICompanionEntity companion) {
        // 恢复背包（掉落模式下 savedInventory 为空，这里不会添加任何东西）
        for (ItemStack stack : savedInventory) {
            companion.addToInventory(stack.copy());
        }
        // 恢复主手（掉落模式下 savedMainHand 为空）
        if (!savedMainHand.isEmpty()) {
            companion.setItemInHand(
                    net.minecraft.world.InteractionHand.MAIN_HAND, savedMainHand.copy());
        }
        // 恢复模式
        companion.setMode(savedMode);
        // 重生后饥饿值和生命值回满
        companion.setHunger(companion.getMaxHunger());
        companion.setHealth(companion.getMaxHealth());
    }

    private void clearSavedData() {
        savedInventory.clear();
        savedMainHand = ItemStack.EMPTY;
        respawnCountdown = -1;
    }

    // ── 通知拥有者 ───────────────────────────────────────────────────────────

    private void notifyOwner(MinecraftServer server, String message) {
        if (ownerUuid == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("§7[AI 同伴] " + message + "§r"));
        }
    }
}
