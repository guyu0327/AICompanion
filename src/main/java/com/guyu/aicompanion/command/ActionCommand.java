package com.guyu.aicompanion.command;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.action.Action;
import com.guyu.aicompanion.action.ActionType;
import com.guyu.aicompanion.action.ActionExecutor;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Debug commands for manually triggering actions on AI companions.
 * <p>
 * Usage:
 * <pre>
 *   /aicompanion action &lt;player&gt; move   &lt;x&gt; &lt;y&gt; &lt;z&gt;
 *   /aicompanion action &lt;player&gt; mine   &lt;x&gt; &lt;y&gt; &lt;z&gt;
 *   /aicompanion action &lt;player&gt; attack &lt;target-entity&gt;
 *   /aicompanion action &lt;player&gt; chat   &lt;message&gt;
 *   /aicompanion action &lt;player&gt; wait
 *   /aicompanion action &lt;player&gt; eat
 *   /aicompanion action &lt;player&gt; sleep
 *   /aicompanion action &lt;player&gt; drop
 *   /aicompanion action &lt;player&gt; cancel
 *   /aicompanion action &lt;player&gt; status
 * </pre>
 * The {@code <player>} selector picks the nearest AI companion to the executing player.
 */
public class ActionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("aicompanion")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("action")
                    // ── move ───────────────────────────────────────────────
                    .then(Commands.literal("move")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(ActionCommand::execMove)))
                    // ── mine ───────────────────────────────────────────────
                    .then(Commands.literal("mine")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(ActionCommand::execMine)))
                    // ── attack ─────────────────────────────────────────────
                    .then(Commands.literal("attack")
                        .then(Commands.argument("target", EntityArgument.entity())
                            .executes(ActionCommand::execAttack)))
                    // ── chat ───────────────────────────────────────────────
                    .then(Commands.literal("chat")
                        .then(Commands.argument("message", net.minecraft.commands.arguments.MessageArgument.message())
                            .executes(ActionCommand::execChat)))
                    // ── simple actions ─────────────────────────────────────
                    .then(Commands.literal("wait").executes(ctx -> execSimple(ctx, ActionType.WAIT)))
                    .then(Commands.literal("eat").executes(ctx -> execSimple(ctx, ActionType.EAT)))
                    .then(Commands.literal("sleep").executes(ctx -> execSimple(ctx, ActionType.SLEEP)))
                    .then(Commands.literal("drop").executes(ctx -> execSimple(ctx, ActionType.DROP_ITEM)))
                    // ── control ────────────────────────────────────────────
                    .then(Commands.literal("cancel").executes(ActionCommand::execCancel))
                    .then(Commands.literal("status").executes(ActionCommand::execStatus))
                )
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Find the closest AI companion to the command executor (within 16 blocks).
     */
    private static AICompanionEntity findCompanion(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var level = source.getLevel();
        var pos   = source.getPosition();
        double best = Double.MAX_VALUE;
        AICompanionEntity result = null;
        for (var entity : level.getEntitiesOfClass(AICompanionEntity.class,
                net.minecraft.world.phys.AABB.ofSize(pos, 32, 32, 32))) {
            double d = entity.distanceToSqr(pos.x, pos.y, pos.z);
            if (d < best) {
                best   = d;
                result = entity;
            }
        }
        return result;
    }

    private static int sendError(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    // ── Action executors ────────────────────────────────────────────────────

    private static int execMove(CommandContext<CommandSourceStack> ctx) {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        companion.getActionExecutor().startAction(Action.move(pos, "手动命令"));
        ctx.getSource().sendSuccess(() ->
                Component.literal("AI Companion 正在前往 " + pos.toShortString()), true);
        return 1;
    }

    private static int execMine(CommandContext<CommandSourceStack> ctx) {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        companion.getActionExecutor().startAction(Action.mine(pos, "手动命令"));
        ctx.getSource().sendSuccess(() ->
                Component.literal("AI Companion 开始挖掘 " + pos.toShortString()), true);
        return 1;
    }

    private static int execAttack(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        var target = EntityArgument.getEntity(ctx, "target");
        String name = target.getType().builtInRegistryHolder().key().identifier().getPath();
        companion.getActionExecutor().startAction(Action.attack(name, 32, "手动命令"));
        ctx.getSource().sendSuccess(() ->
                Component.literal("AI Companion 开始攻击 " + target.getName().getString()), true);
        return 1;
    }

    private static int execChat(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        net.minecraft.network.chat.Component msgComponent =
            net.minecraft.commands.arguments.MessageArgument.getMessage(ctx, "message");
        companion.getActionExecutor().startAction(Action.chat(msgComponent.getString(), "手动命令"));
        return 1;
    }

    private static int execSimple(CommandContext<CommandSourceStack> ctx, ActionType type) {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        companion.getActionExecutor().startAction(Action.simple(type, "手动命令"));
        ctx.getSource().sendSuccess(() ->
                Component.literal("AI Companion 执行 " + type.getDisplayName()), true);
        return 1;
    }

    private static int execCancel(CommandContext<CommandSourceStack> ctx) {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        companion.getActionExecutor().cancel();
        ctx.getSource().sendSuccess(() ->
                Component.literal("AI Companion 已停止当前动作"), true);
        return 1;
    }

    private static int execStatus(CommandContext<CommandSourceStack> ctx) {
        AICompanionEntity companion = findCompanion(ctx);
        if (companion == null) return sendError(ctx, "附近没有找到 AI Companion");
        ActionExecutor exec = companion.getActionExecutor();
        String status = String.format(
                "状态: %s  |  当前动作: %s  |  HP: %.1f/%.1f  |  位置: %s",
                exec.getState().name(),
                exec.getCurrentAction() != null ? exec.getCurrentAction() : "无",
                companion.getHealth(),
                companion.getMaxHealth(),
                companion.blockPosition().toShortString());
        ctx.getSource().sendSuccess(() -> Component.literal(status), false);
        return 1;
    }
}
