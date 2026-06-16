package com.guyu.aicompanion.event;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.List;

/**
 * Handles player chat events for AI companion interaction.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>All chat messages within 48 blocks are recorded in each companion's chat history
 *       (so the AI can "overhear" conversations for context).</li>
 *   <li>Messages that mention a companion (via {@code @AI}, {@code @同伴}, or the
 *       companion's actual name) trigger an <b>immediate</b> AI decision so the
 *       companion responds promptly.</li>
 * </ul>
 */
public class ChatHandler {

    /** Range within which companions can "hear" chat messages. */
    private static final double HEARING_RANGE = 48.0;

    /**
     * Called on the server thread whenever a player sends a chat message.
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player.level().isClientSide()) return;

        String message = event.getRawText();
        String playerName = event.getUsername();

        // Find all companions within hearing range
        AABB area = player.getBoundingBox().inflate(HEARING_RANGE);
        List<AICompanionEntity> companions = player.level()
                .getEntitiesOfClass(AICompanionEntity.class, area);

        if (companions.isEmpty()) return;

        // Determine if the message is directed at any companion
        boolean isDirected = false;
        for (AICompanionEntity companion : companions) {
            String companionName = companion.getName().getString();
            if (isMentioning(message, companionName)) {
                isDirected = true;
            }
        }

        // Record in every nearby companion's chat history
        for (AICompanionEntity companion : companions) {
            companion.getAiTickHandler().addPlayerMessage(playerName, message);

            // If the message is directed at this companion (or any companion),
            // trigger an immediate AI decision
            if (isDirected) {
                AICompanion.LOGGER.info("[Chat] {} 对 AI 说: {}", playerName, message);
                companion.getAiTickHandler().tickNow();
            }
        }
    }

    /**
     * Check if a chat message is mentioning a companion.
     * Matches {@code @AI}, {@code @同伴}, or the companion's name (case-insensitive).
     */
    private boolean isMentioning(String message, String companionName) {
        String lower = message.toLowerCase();

        // Explicit @AI or @同伴 prefix
        if (lower.startsWith("@ai") || lower.contains("@ai ") ||
            lower.startsWith("@ai同伴") || lower.contains("@同伴")) {
            return true;
        }

        // Companion's actual name (case-insensitive)
        if (companionName != null && !companionName.isEmpty()) {
            String nameLower = companionName.toLowerCase();
            if (lower.contains(nameLower)) {
                return true;
            }
        }

        return false;
    }
}
