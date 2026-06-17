package com.guyu.aicompanion.command;

import com.guyu.aicompanion.AICompanion;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

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
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();

        var entity = AICompanion.COMPANION.get().spawn(
                level,
                BlockPos.containing(pos),
                EntitySpawnReason.COMMAND
        );

        if (entity != null) {
            // 绑定拥有者为命令执行者（如果是玩家）
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                entity.setOwnerUuid(player.getUUID());
            }
            source.sendSuccess(() -> Component.translatable("command.aicompanion.spawn.success"), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable("command.aicompanion.spawn.failed"));
            return 0;
        }
    }
}
