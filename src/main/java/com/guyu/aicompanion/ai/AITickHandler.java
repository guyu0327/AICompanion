package com.guyu.aicompanion.ai;

import com.google.gson.JsonObject;
import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.action.Action;
import com.guyu.aicompanion.action.ActionExecutor;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.guyu.aicompanion.state.GameState;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AI 决策主循环。从 AICompanionEntity.tick() 每 tick 调用。
 * <p>
 * 流程：
 * <ol>
 *   <li>空闲且冷却结束 → 收集游戏状态</li>
 *   <li>构建提示词并异步发送 HTTP 请求到 AI API</li>
 *   <li>收到响应（调度回服务器线程）：解析 → 执行动作</li>
 *   <li>动作完成 → 重置冷却，回到第 1 步</li>
 * </ol>
 */
public class AITickHandler {

    /** 同伴空闲时两次 AI 决策之间的 tick 数 */
    private static final int DECISION_INTERVAL_TICKS = 60;  // 约 3 秒
    /** 两次请求之间的最小间隔（毫秒），防止短时间内连续请求 */
    private static final long MIN_REQUEST_INTERVAL_MS = 1000;

    private final AICompanionEntity companion;
    private final ChatHistory chatHistory;

    private int ticksSinceLastDecision = 0;
    private boolean awaitingResponse = false;
    private long lastRequestTimeMs = 0;

    public AITickHandler(AICompanionEntity companion) {
        this.companion = companion;
        this.chatHistory = new ChatHistory(companion.getName().getString());
    }

    /**
     * 从 AICompanionEntity.tick() 每个服务器 tick 调用。
     * 驱动 AI 决策状态机。
     */
    public void tick() {
        if (companion.level().isClientSide()) return;

        ActionExecutor exec = getExecutor();
        if (exec == null) return;

        // 如果仍在处理中，等待
        if (awaitingResponse) return;

        // 如果同伴正在执行动作，等待完成
        if (!exec.isIdle()) {
            ticksSinceLastDecision = 0;
            return;
        }

        // 倒计时到下次决策
        ticksSinceLastDecision++;
        if (ticksSinceLastDecision < DECISION_INTERVAL_TICKS) return;

        // 到时间做新的 AI 决策了
        ticksSinceLastDecision = 0;
        requestDecision(exec);
    }

    /**
     * 收集游戏状态，构建提示词，异步发送请求到 AI API。
     * 响应到达后解析并在主服务器线程上调度动作执行。
     */
    private void requestDecision(ActionExecutor exec) {
        // 限流：确保两次请求之间有最小间隔
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTimeMs;
        if (elapsed < MIN_REQUEST_INTERVAL_MS && lastRequestTimeMs > 0) {
            // 延迟到满足间隔后再发送
            long waitTicks = (MIN_REQUEST_INTERVAL_MS - elapsed) / 50 + 1;
            ticksSinceLastDecision = DECISION_INTERVAL_TICKS - (int) waitTicks;
            return;
        }
        lastRequestTimeMs = now;

        awaitingResponse = true;

        // 1. 收集游戏状态
        JsonObject gameState = GameState.collect(companion, chatHistory);

        // 2. 构建提示词消息
        String name = companion.getName().getString();
        List<AIService.Message> messages =
                PromptBuilder.buildMessages(name, gameState, chatHistory);

        // 3. 读取配置
        String model = com.guyu.aicompanion.Config.MODEL_NAME.get();
        double temperature = com.guyu.aicompanion.Config.TEMPERATURE.get();
        int maxTokens = com.guyu.aicompanion.Config.MAX_TOKENS.get();

        AICompanion.LOGGER.debug("[AI] {} 正在思考...", name);

        // 4. 发送异步请求
        CompletableFuture<String> future =
                AIService.chatAsync(messages, model, temperature, maxTokens);

        ServerLevel level = (ServerLevel) companion.level();

        future.thenAccept(response -> {
            AICompanion.LOGGER.debug("[AI] 收到 {} 的回复 (HTTP线程), 长度: {}",
                    name, response != null ? response.length() : "null");

            // 调度回服务器线程 — Minecraft 世界修改
            // 必须在主线程上执行
            var server = level.getServer();
            if (server == null) {
                AICompanion.LOGGER.error("[AI] MinecraftServer 为 null，无法执行动作！");
                awaitingResponse = false;
                return;
            }

            server.execute(() -> {
                try {
                    awaitingResponse = false;
                    AICompanion.LOGGER.debug("[AI] {} 回复: {}", name, response);

                    // 将 AI 的回复记录到聊天历史中
                    chatHistory.add(name, response);

                    // 从响应中提取 JSON（处理 markdown 代码块等）
                    String json = extractJson(response);
                    AICompanion.LOGGER.debug("[AI] 提取的JSON: {}", json);

                    // 将响应解析为 Action
                    Action action = Action.fromJson(json);
                    AICompanion.LOGGER.debug("[AI] 解析的动作: {}", action);

                    if (action != null && action.getType() != null) {
                        exec.startAction(action);
                    } else {
                        AICompanion.LOGGER.warn("[AI] 动作解析结果为空！");
                    }
                } catch (Exception e) {
                    awaitingResponse = false;
                    AICompanion.LOGGER.error("[AI] 处理 {} 的回复失败", name, e);
                }
            });
        }).exceptionally(e -> {
            var server = level.getServer();
            if (server != null) {
                server.execute(() -> {
                    awaitingResponse = false;
                    AICompanion.LOGGER.error("[AI] {} 的请求失败: {}",
                            name, e.getMessage());
                });
            } else {
                awaitingResponse = false;
            }
            return null;
        });
    }

    /**
     * 从 AI 响应中提取 JSON。AI 可能将 JSON 包裹在
     * markdown 代码块中或添加额外文字 — 我们尝试找到 JSON 对象。
     */
    private String extractJson(String response) {
        if (response == null) return "{}";

        String trimmed = response.trim();

        // 如果已经以 { 开头，假设是干净的 JSON
        if (trimmed.startsWith("{")) return trimmed;

        // 尝试从 ```json ... ``` 代码块中提取
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart) + 1;
            int jsonEnd = trimmed.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return trimmed.substring(contentStart, jsonEnd).trim();
            }
        }

        // 尝试从 ``` ... ``` 代码块中提取
        int codeStart = trimmed.indexOf("```");
        if (codeStart >= 0) {
            int contentStart = trimmed.indexOf('\n', codeStart) + 1;
            int codeEnd = trimmed.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                String inner = trimmed.substring(contentStart, codeEnd).trim();
                if (inner.startsWith("{")) return inner;
            }
        }

        // 尝试找到第一个 { 和最后一个 }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        // 放弃 — 原样返回；Action.fromJson 会妥善处理
        return trimmed;
    }

    public boolean isBusy() {
        return awaitingResponse;
    }

    public boolean isIdle() {
        return !awaitingResponse && getExecutor() != null && getExecutor().isIdle();
    }

    public ChatHistory getChatHistory() {
        return chatHistory;
    }

    /**
     * 在下一个 tick 立即触发 AI 决策（绕过冷却）。
     * 当玩家向此同伴发送消息时调用。
     */
    public void tickNow() {
        ticksSinceLastDecision = DECISION_INTERVAL_TICKS;  // will trigger on next tick()
    }

    /** 将玩家消息添加到同伴的聊天历史中 */
    public void addPlayerMessage(String playerName, String message) {
        chatHistory.add(playerName, message);
    }

    /** 重置状态 — 例如取消或切换模式时 */
    public void reset() {
        awaitingResponse = false;
        ticksSinceLastDecision = 0;
    }

    private ActionExecutor getExecutor() {
        return companion.getActionExecutor();
    }
}
