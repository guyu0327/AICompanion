package com.guyu.aicompanion.command;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.guyu.aicompanion.entity.CompanionTracker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

public class SpawnCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("aicompanion")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                    .executes(SpawnCommand::execute)
                )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("只有玩家才能执行此命令"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        CompanionTracker tracker = CompanionTracker.get();

        // ── 检查同伴是否正在重生 ──────────────────────────────────────────────
        if (tracker.isRespawning()) {
            int seconds = tracker.getRespawnSecondsRemaining();
            source.sendSuccess(() ->
                    Component.literal("同伴正在重生中，还需等待 " + seconds + " 秒"), false);
            return 1;
        }

        // ── 查找世界中已有的同伴 ──────────────────────────────────────────────
        AICompanionEntity existing = findCompanionInLevel(level);

        if (existing != null) {
            // 同伴已存在 → 传送到玩家附近
            BlockPos targetPos = player.blockPosition();
            existing.teleportTo(
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5);
            // 清除当前动作，避免传送后继续执行之前的动作
            existing.getActionExecutor().cancel();
            source.sendSuccess(() ->
                    Component.literal("同伴已传送到你身边"), true);
            return 1;
        }

        // ── 没有同伴 → 生成新的 ──────────────────────────────────────────────
        var entity = AICompanion.COMPANION.get().spawn(
                level,
                BlockPos.containing(source.getPosition()),
                EntitySpawnReason.COMMAND
        );

        if (entity != null) {
            entity.setOwnerUuid(player.getUUID());
            tracker.setOwnerUuid(player.getUUID());
            source.sendSuccess(() ->
                    Component.translatable("command.aicompanion.spawn.success"), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable("command.aicompanion.spawn.failed"));
            return 0;
        }
    }

    /**
     * 在指定 level 中查找 AI 同伴实体。
     */
    private static AICompanionEntity findCompanionInLevel(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof AICompanionEntity companion && !entity.isRemoved()) {
                return companion;
            }
        }
        return null;
    }
}
