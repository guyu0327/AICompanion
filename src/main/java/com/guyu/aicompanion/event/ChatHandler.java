package com.guyu.aicompanion.event;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.List;

/**
 * 处理玩家聊天事件以实现 AI 同伴交互。
 * <p>
 * 行为：
 * <ul>
 *   <li>48 格内的所有聊天消息都会记录到每个同伴的聊天历史中
 *       （让 AI 可以"旁听"对话获取上下文）。</li>
 *   <li>提及同伴的消息（通过 {@code @AI}、{@code @同伴} 或同伴的
 *       实际名字）会触发<b>立即</b> AI 决策，以便同伴及时回应。</li>
 * </ul>
 */
public class ChatHandler {

    /** 同伴能"听到"聊天消息的范围 */
    private static final double HEARING_RANGE = 48.0;

    /**
     * 玩家发送聊天消息时在服务器线程上调用。
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player.level().isClientSide()) return;

        String message = event.getRawText();
        String playerName = event.getUsername();

        // 找到听力范围内的所有同伴
        AABB area = player.getBoundingBox().inflate(HEARING_RANGE);
        List<AICompanionEntity> companions = player.level()
                .getEntitiesOfClass(AICompanionEntity.class, area);

        if (companions.isEmpty()) return;

        // 判断消息是否针对某个同伴
        boolean isDirected = false;
        for (AICompanionEntity companion : companions) {
            String companionName = companion.getName().getString();
            if (isMentioning(message, companionName)) {
                isDirected = true;
            }
        }

        // 记录到附近所有同伴的聊天历史中
        for (AICompanionEntity companion : companions) {
            companion.getAiTickHandler().addPlayerMessage(playerName, message);

            // 如果消息是针对此同伴（或任意同伴），
            // 触发立即 AI 决策
            if (isDirected) {
                AICompanion.LOGGER.info("[Chat] {} 对 AI 说: {}", playerName, message);
                companion.getAiTickHandler().tickNow();
            }
        }
    }

    /**
     * 检查聊天消息是否提及同伴。
     * 匹配 {@code @AI}、{@code @同伴} 或同伴的名字（不区分大小写）。
     */
    private boolean isMentioning(String message, String companionName) {
        String lower = message.toLowerCase();

        // 显式的 @AI 或 @同伴 前缀
        if (lower.startsWith("@ai") || lower.contains("@ai ") ||
            lower.startsWith("@ai同伴") || lower.contains("@同伴")) {
            return true;
        }

        // 同伴的实际名字（不区分大小写）
        if (companionName != null && !companionName.isEmpty()) {
            String nameLower = companionName.toLowerCase();
            if (lower.contains(nameLower)) {
                return true;
            }
        }

        return false;
    }
}
