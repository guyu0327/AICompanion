package com.guyu.aicompanion.ai;

import com.google.gson.JsonObject;
import com.guyu.aicompanion.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the message list sent to the AI API.
 * <p>
 * The system prompt tells the AI who it is, what actions are available,
 * and what JSON format to respond with. The user message contains the
 * current game state + chat history as context.
 */
public class PromptBuilder {

    /**
     * Build the full message list for an API request.
     *
     * @param companionName  the companion's display name
     * @param gameState      current game state as JSON
     * @param chatHistory    recent chat messages
     * @return list of messages in OpenAI chat format
     */
    public static List<AIService.Message> buildMessages(
            String companionName,
            JsonObject gameState,
            ChatHistory chatHistory) {
        List<AIService.Message> messages = new ArrayList<>();

        // System prompt
        String systemPrompt = buildSystemPrompt(companionName);
        messages.add(new AIService.Message("system", systemPrompt));

        // Historical chat messages as context
        if (chatHistory != null && chatHistory.size() > 0) {
            for (AIService.Message histMsg : chatHistory.toApiMessages()) {
                messages.add(histMsg);
            }
        }

        // Current state as user message
        String userMsg = "当前游戏状态:\n" + gameState.toString();
        userMsg += "\n\n请根据以上状态，分析当前情况并决定下一步行动。以JSON格式返回你的决策。";
        messages.add(new AIService.Message("user", userMsg));

        return messages;
    }

    private static String buildSystemPrompt(String companionName) {
        String basePrompt = Config.SYSTEM_PROMPT.get();

        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt).append("\n\n");

        sb.append("你是Minecraft中的一个AI同伴。你的名字叫 ").append(companionName).append("。\n");
        sb.append("你需要观察周围的游戏世界状态，分析情况，并决定下一步行动。\n\n");

        sb.append("## 可用动作\n");
        sb.append("你可以执行以下动作（返回 action 字段）:\n");
        sb.append("- `move`: 移动到指定位置。需要提供 target: [x, y, z]\n");
        sb.append("- `mine`: 挖掘指定位置的方块。需要提供 target: [x, y, z]\n");
        sb.append("- `attack`: 攻击指定类型的实体。需要提供 targetName: \"实体类型名\"\n");
        sb.append("- `chat`: 说一句话。需要提供 message: \"内容\"\n");
        sb.append("- `wait`: 等待/原地待命\n");
        sb.append("- `eat`: 进食（需要手中有食物）\n");
        sb.append("- `sleep`: 睡觉休息\n");
        sb.append("- `wake_up`: 从睡眠中醒来\n");
        sb.append("- `drop_item`: 丢弃手中的物品\n");
        sb.append("- `use_item`: 使用手中的物品\n");
        sb.append("- `place_block`: 在指定位置放置方块。需要提供 target: [x, y, z]\n\n");

        sb.append("## 输出格式\n");
        sb.append("你必须且仅返回一个JSON对象，不要有任何其他文字、解释或markdown代码块标记:\n");
        sb.append("{\n");
        sb.append("  \"action\": \"动作名\",\n");
        sb.append("  \"target\": [x, y, z],       // 仅 move/mine/place_block 需要\n");
        sb.append("  \"targetName\": \"实体类型\",   // 仅 attack 需要\n");
        sb.append("  \"message\": \"说话内容\",       // 仅 chat 需要\n");
        sb.append("  \"reason\": \"决策原因\"         // 总是需要提供，说明你为什么这么做\n");
        sb.append("}\n\n");

        sb.append("## 游戏状态说明\n");
        sb.append("你会收到当前游戏状态的JSON，其中包含:\n");
        sb.append("- `nearbyBlocks`: 附近方块信息，每种方块包含 `count`(总数) 和 `nearest`(最近几个的坐标 [x,y,z])\n");
        sb.append("- `nearbyEntities`: 附近实体列表，每个包含 `type`(类型名)、`pos`(坐标) 和 `distance`(距离)\n");
        sb.append("**你可以直接使用这些坐标作为 move/mine/place_block 的 target**\n");
        sb.append("**你可以直接使用 nearbyEntities 中的 type 作为 attack 的 targetName**\n\n");

        sb.append("## 行动建议\n");
        sb.append("- **主动行动**: 不要总是 chat 或 wait！你应该根据环境 actively 采取行动\n");
        sb.append("- **采集资源**: 看到树木(oak_log等)就去 mine，看到矿石就去挖\n");
        sb.append("- **探索移动**: 如果周围没有有价值的东西，move 到新的区域探索\n");
        sb.append("- **战斗**: 看到敌对生物(如 zombie, skeleton, spider)就 attack 它们\n");
        sb.append("- **安全**: 血量低时考虑 retreat（远离危险方向）或 eat\n");
        sb.append("- **时间感知**: 天黑(18:00后)时注意怪物出没，考虑 sleep 或 build shelter\n");
        sb.append("- **与玩家互动**: 可以通过 chat 与玩家交流，但不要每次都 chat\n");

        return sb.toString();
    }
}
