package com.guyu.aicompanion.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles HTTP communication with an OpenAI-compatible Chat Completions API.
 * <p>
 * Uses Java's built-in {@link HttpClient} (available since Java 11, MC 26.1 ships Java 25).
 * All requests are asynchronous to avoid blocking the server tick thread.
 */
public class AIService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    /** A single chat message in the OpenAI format. */
    public record Message(String role, String content) {}

    /** A single choice in the API response. */
    public record Choice(int index, Message message, String finishReason) {}

    /** Parsed API response. */
    public record Response(String content, String finishReason) {}

    /**
     * Send a chat completion request asynchronously.
     *
     * @return CompletableFuture that completes with the AI's response text,
     *         or completes exceptionally on error.
     */
    public static CompletableFuture<String> chatAsync(
            List<Message> messages,
            String model,
            double temperature,
            int maxTokens) {
        try {
            String apiUrl = Config.API_URL.get();
            String endpoint = Config.API_ENDPOINT.get();
            String apiKey = Config.API_KEY.get();
            int timeout = Config.API_TIMEOUT.get();

            if (apiUrl == null || apiUrl.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("API URL 未配置"));
            }
            if (apiKey == null || apiKey.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("API Key 未配置"));
            }

            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxTokens);

            JsonArray msgsArr = new JsonArray();
            for (Message msg : messages) {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.role());
                m.addProperty("content", msg.content());
                msgsArr.add(m);
            }
            body.add("messages", msgsArr);

            String url = buildUrl(apiUrl, endpoint);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(timeout))
                    .build();

            AICompanion.LOGGER.info("[AI] 发送请求: {}", url);
            AICompanion.LOGGER.debug("[AI] 请求体: {}", body);

            return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int statusCode = response.statusCode();
                        String responseBody = response.body();
                        AICompanion.LOGGER.debug("[AI] 响应状态: {}", statusCode);
                        AICompanion.LOGGER.debug("[AI] 响应体: {}", responseBody);

                        if (statusCode != 200) {
                            AICompanion.LOGGER.error("[AI] API 返回 {} — URL: {}", statusCode, url);
                            throw new RuntimeException(
                                    "API 返回错误 " + statusCode + " (URL: " + url + "): " + responseBody);
                        }
                        return parseResponse(responseBody);
                    });
        } catch (Exception e) {
            AICompanion.LOGGER.error("[AI] 构建请求失败", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Intelligently join the base URL and endpoint, avoiding duplicate path segments.
     * Examples:
     * <ul>
     *   <li>{@code "https://api.openai.com"} + {@code "/v1/chat/completions"} → {@code "https://api.openai.com/v1/chat/completions"}</li>
     *   <li>{@code "https://api.openai.com/v1"} + {@code "/v1/chat/completions"} → {@code "https://api.openai.com/v1/chat/completions"}</li>
     *   <li>{@code "https://my.api.com/"} + {@code "chat/completions"} → {@code "https://my.api.com/chat/completions"}</li>
     *   <li>{@code "https://my.api.com/base/"} + {@code "/v1/chat/completions"} → {@code "https://my.api.com/base/v1/chat/completions"}</li>
     * </ul>
     */
    static String buildUrl(String apiUrl, String endpoint) {
        String base = apiUrl.replaceAll("/+$", "");   // strip trailing slashes
        String path = endpoint;

        // If base ends with a path segment that is also the start of endpoint,
        // remove the duplicate.  e.g. base=".../v1" + endpoint="/v1/chat/..."
        if (!path.isEmpty() && path.startsWith("/")) {
            String[] baseParts = base.split("/");
            String lastBasePart = baseParts.length > 0 ? baseParts[baseParts.length - 1] : "";
            // Check if endpoint starts with "/<lastPart>/"
            if (!lastBasePart.isEmpty() && path.startsWith("/" + lastBasePart + "/")) {
                path = path.substring(lastBasePart.length() + 1);  // remove "/<lastPart>"
            }
        }

        // Ensure exactly one slash between base and path
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        } else {
            return base + path;
        }
    }

    /**
     * Parse the API response JSON and extract the AI's reply text.
     * <p>
     * Tries multiple extraction strategies to support different API providers:
     * <ol>
     *   <li>OpenAI standard: {@code choices[0].message.content}</li>
     *   <li>Alternative fields: {@code result}, {@code output}, {@code response}, {@code text}</li>
     *   <li>Streaming-style: {@code delta.content}</li>
     * </ol>
     */
    private static String parseResponse(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) throw new RuntimeException("Empty response");

            // Check for API error
            if (obj.has("error")) {
                JsonElement errorEl = obj.get("error");
                String errMsg;
                if (errorEl.isJsonObject()) {
                    JsonObject error = errorEl.getAsJsonObject();
                    errMsg = error.has("message")
                            ? error.get("message").getAsString()
                            : error.toString();
                } else {
                    errMsg = errorEl.getAsString();
                }
                throw new RuntimeException("API error: " + errMsg);
            }

            // Strategy 1: OpenAI standard format — choices[0].message.content
            String content = extractFromChoices(obj);
            if (content != null) return content;

            // Strategy 1.5: Anthropic Messages API — content is an array of blocks
            // e.g. [{"type":"thinking","thinking":"..."}, {"type":"text","text":"actual response"}]
            content = extractFromAnthropicContent(obj);
            if (content != null) return content;

            // Strategy 2: Direct top-level fields used by some providers
            for (String field : new String[]{
                    "result", "output", "response", "text",
                    "completion", "data", "message", "content"}) {
                content = extractStringField(obj, field);
                if (content != null) return content;
            }

            // Strategy 3: Nested in "data" array (Azure-style)
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                JsonArray dataArr = obj.getAsJsonArray("data");
                if (!dataArr.isEmpty() && dataArr.get(0).isJsonObject()) {
                    JsonObject dataObj = dataArr.get(0).getAsJsonObject();
                    for (String field : new String[]{
                            "text", "content", "message", "result", "output"}) {
                        content = extractStringField(dataObj, field);
                        if (content != null) return content;
                    }
                }
            }

            // Nothing worked — log the full response for debugging
            AICompanion.LOGGER.error("[AI] 无法从响应中提取内容，原始响应: {}", json);
            throw new RuntimeException(
                    "无法解析 API 响应格式 (已尝试 choices/result/output/text 等字段). " +
                    "响应前200字符: " + json.substring(0, Math.min(json.length(), 200)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse API response: " + e.getMessage(), e);
        }
    }

    /** Try to extract content from OpenAI-style choices array. */
    private static String extractFromChoices(JsonObject obj) {
        if (!obj.has("choices")) return null;
        JsonElement choicesEl = obj.get("choices");
        if (!choicesEl.isJsonArray()) return null;
        JsonArray choices = choicesEl.getAsJsonArray();
        if (choices.isEmpty()) return null;

        JsonElement firstEl = choices.get(0);
        if (!firstEl.isJsonObject()) return null;
        JsonObject firstChoice = firstEl.getAsJsonObject();

        // choices[0].message.content
        if (firstChoice.has("message")) {
            JsonElement msgEl = firstChoice.get("message");
            if (msgEl.isJsonObject()) {
                JsonObject message = msgEl.getAsJsonObject();
                if (message.has("content")) {
                    JsonElement contentEl = message.get("content");
                    if (!contentEl.isJsonNull()) {
                        return contentEl.isJsonPrimitive()
                                ? contentEl.getAsString()
                                : contentEl.toString();
                    }
                }
            }
            // choices[0].message as a plain string (some providers)
            if (msgEl.isJsonPrimitive()) {
                return msgEl.getAsString();
            }
        }

        // choices[0].delta.content (streaming-style)
        if (firstChoice.has("delta")) {
            JsonElement deltaEl = firstChoice.get("delta");
            if (deltaEl.isJsonObject()) {
                JsonObject delta = deltaEl.getAsJsonObject();
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    return delta.get("content").getAsString();
                }
            }
        }

        // choices[0].text (legacy completions API)
        if (firstChoice.has("text")) {
            return firstChoice.get("text").getAsString();
        }

        return null;
    }

    /**
     * Extract content from Anthropic Messages API format.
     * The response has a top-level "content" array of blocks:
     * <pre>
     * {
     *   "content": [
     *     {"type": "thinking", "thinking": "..."},
     *     {"type": "text", "text": "actual response text"}
     *   ]
     * }
     * </pre>
     * We look for the first block with type "text" and return its "text" field.
     */
    private static String extractFromAnthropicContent(JsonObject obj) {
        if (!obj.has("content")) return null;
        JsonElement contentEl = obj.get("content");
        if (!contentEl.isJsonArray()) return null;

        JsonArray contentArr = contentEl.getAsJsonArray();
        StringBuilder sb = new StringBuilder();

        for (JsonElement blockEl : contentArr) {
            if (!blockEl.isJsonObject()) continue;
            JsonObject block = blockEl.getAsJsonObject();

            // Only extract "text" type blocks; skip "thinking" blocks
            String type = block.has("type") ? block.get("type").getAsString() : "";
            if ("text".equals(type) && block.has("text")) {
                JsonElement textEl = block.get("text");
                if (!textEl.isJsonNull() && textEl.isJsonPrimitive()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(textEl.getAsString());
                }
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Try to extract a string value from a top-level field. */
    private static String extractStringField(JsonObject obj, String field) {
        if (!obj.has(field)) return null;
        JsonElement el = obj.get(field);
        if (el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        // If it's an object, try to get "content" or "text" from it
        if (el.isJsonObject()) {
            JsonObject nested = el.getAsJsonObject();
            for (String inner : new String[]{"content", "text", "message"}) {
                if (nested.has(inner) && nested.get(inner).isJsonPrimitive()) {
                    return nested.get(inner).getAsString();
                }
            }
        }
        return null;
    }
}
