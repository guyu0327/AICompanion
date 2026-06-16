package com.guyu.aicompanion.ai;

import com.google.gson.JsonObject;
import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.action.Action;
import com.guyu.aicompanion.action.ActionExecutor;
import com.guyu.aicompanion.state.GameState;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main AI decision loop.  Called every tick from AICompanionEntity.tick().
 * <p>
 * Flow:
 * <ol>
 *   <li>When idle and cooldown expired → collect game state</li>
 *   <li>Build prompt and send async HTTP request to AI API</li>
 *   <li>On response (scheduled back to server thread): parse → execute action</li>
 *   <li>When action completes → reset cooldown, go back to step 1</li>
 * </ol>
 */
public class AITickHandler {

    /** Ticks between AI decisions when the companion is idle. */
    private static final int DECISION_INTERVAL_TICKS = 60;  // ~3 seconds

    private final net.minecraft.world.entity.Mob companion;
    private final ChatHistory chatHistory = new ChatHistory();

    private int ticksSinceLastDecision = 0;
    private boolean awaitingResponse = false;
    private final AtomicBoolean responseHandled = new AtomicBoolean(false);

    public AITickHandler(net.minecraft.world.entity.Mob companion) {
        this.companion = companion;
    }

    /**
     * Called every server tick from AICompanionEntity.tick().
     * Drives the AI decision state machine.
     */
    public void tick() {
        if (companion.level().isClientSide()) return;

        ActionExecutor exec = getExecutor();
        if (exec == null) return;

        // If a response just arrived, handle it on the server thread
        // (the CompletableFuture callback runs on an HTTP thread)
        if (responseHandled.compareAndSet(true, false)) {
            // Already handled in the callback — nothing extra to do here
        }

        // If we're still processing, wait
        if (awaitingResponse) return;

        // If the companion is executing an action, wait for it to finish
        if (!exec.isIdle()) {
            ticksSinceLastDecision = 0;
            return;
        }

        // Countdown to next decision
        ticksSinceLastDecision++;
        if (ticksSinceLastDecision < DECISION_INTERVAL_TICKS) return;

        // Time for a new AI decision
        ticksSinceLastDecision = 0;
        requestDecision(exec);
    }

    /**
     * Collect game state, build prompt, send async request to the AI API.
     * When the response arrives, parse it and schedule action execution
     * on the main server thread.
     */
    private void requestDecision(ActionExecutor exec) {
        awaitingResponse = true;

        // 1. Collect game state
        JsonObject gameState = GameState.collect(companion, chatHistory);

        // 2. Build prompt messages
        String name = companion.getName().getString();
        List<AIService.Message> messages =
                PromptBuilder.buildMessages(name, gameState, chatHistory);

        // 3. Read config
        String model = com.guyu.aicompanion.Config.MODEL_NAME.get();
        double temperature = com.guyu.aicompanion.Config.TEMPERATURE.get();
        int maxTokens = com.guyu.aicompanion.Config.MAX_TOKENS.get();

        AICompanion.LOGGER.info("[AI] {} 正在思考...", name);

        // 4. Send async request
        CompletableFuture<String> future =
                AIService.chatAsync(messages, model, temperature, maxTokens);

        ServerLevel level = (ServerLevel) companion.level();

        future.thenAccept(response -> {
            AICompanion.LOGGER.info("[AI] 收到 {} 的回复 (HTTP线程), 长度: {}",
                    name, response != null ? response.length() : "null");

            // Schedule back to the server thread — Minecraft world mutations
            // must happen on the main thread
            var server = level.getServer();
            if (server == null) {
                AICompanion.LOGGER.error("[AI] MinecraftServer 为 null，无法执行动作！");
                awaitingResponse = false;
                return;
            }

            server.execute(() -> {
                try {
                    awaitingResponse = false;
                    AICompanion.LOGGER.info("[AI] {} 回复: {}", name, response);

                    // Record the AI's reply in chat history
                    chatHistory.add(name, response);

                    // Extract JSON from response (handles markdown wrapping etc.)
                    String json = extractJson(response);
                    AICompanion.LOGGER.info("[AI] 提取的JSON: {}", json);

                    // Parse the response into an Action
                    Action action = Action.fromJson(json);
                    AICompanion.LOGGER.info("[AI] 解析的动作: {}", action);

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
     * Extract JSON from the AI response.  The AI might wrap the JSON in
     * markdown code blocks or add extra text — we try to find the JSON object.
     */
    private String extractJson(String response) {
        if (response == null) return "{}";

        String trimmed = response.trim();

        // If it already starts with {, assume it's clean JSON
        if (trimmed.startsWith("{")) return trimmed;

        // Try to extract from ```json ... ``` code block
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart) + 1;
            int jsonEnd = trimmed.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return trimmed.substring(contentStart, jsonEnd).trim();
            }
        }

        // Try to extract from ``` ... ``` code block
        int codeStart = trimmed.indexOf("```");
        if (codeStart >= 0) {
            int contentStart = trimmed.indexOf('\n', codeStart) + 1;
            int codeEnd = trimmed.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                String inner = trimmed.substring(contentStart, codeEnd).trim();
                if (inner.startsWith("{")) return inner;
            }
        }

        // Try to find the first { and last }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        // Give up — return as-is; Action.fromJson will handle gracefully
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

    /** Reset state — e.g. when cancelling or switching modes. */
    public void reset() {
        awaitingResponse = false;
        ticksSinceLastDecision = 0;
        responseHandled.set(false);
    }

    private ActionExecutor getExecutor() {
        if (companion instanceof com.guyu.aicompanion.entity.AICompanionEntity ace) {
            return ace.getActionExecutor();
        }
        return null;
    }
}
