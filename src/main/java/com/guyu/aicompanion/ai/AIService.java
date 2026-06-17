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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 处理与 OpenAI 兼容的 Chat Completions API 的 HTTP 通信。
 * <p>
 * 使用 Java 内置的 {@link HttpClient}（Java 11 起可用，MC 26.1 搭载 Java 25）。
 * 所有请求均为异步，避免阻塞服务器 tick 线程。
 */
public class AIService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final ScheduledExecutorService RETRY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AICompanion-Retry");
                t.setDaemon(true);
                return t;
            });

    /** 最大重试次数（不含首次请求） */
    private static final int MAX_RETRIES = 2;
    /** 基础重试延迟（毫秒） */
    private static final long BASE_RETRY_DELAY_MS = 1000;

    /** OpenAI 格式的单个聊天消息 */
    public record Message(String role, String content) {}

    /** API 响应中的单个 choice */
    public record Choice(int index, Message message, String finishReason) {}

    /** 解析后的 API 响应 */
    public record Response(String content, String finishReason) {}

    /**
     * 异步发送 chat completion 请求。
     *
     * @return CompletableFuture，成功时以 AI 的回复文本完成，
     *         出错时以异常完成。
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

            // 构建请求体
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxTokens);

            // JSON 模式：要求 API 返回纯 JSON（部分提供商不支持，可通过配置关闭）
            if (Config.ENABLE_JSON_MODE.get()) {
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_object");
                body.add("response_format", responseFormat);
            }

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

            AICompanion.LOGGER.debug("[AI] 发送请求: {}", url);
            AICompanion.LOGGER.debug("[AI] 请求体: {}", body);

            return sendWithRetry(request, url, MAX_RETRIES);
        } catch (Exception e) {
            AICompanion.LOGGER.error("[AI] 构建请求失败", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 带重试的 HTTP 请求发送。
     * 对 429（限流）、5xx（服务端错误）和连接超时自动重试，使用指数退避。
     */
    private static CompletableFuture<String> sendWithRetry(
            HttpRequest request, String url, int retriesLeft) {
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int statusCode = response.statusCode();
                    String responseBody = response.body();
                    AICompanion.LOGGER.debug("[AI] 响应状态: {}", statusCode);
                    AICompanion.LOGGER.debug("[AI] 响应体: {}", responseBody);

                    if (statusCode == 200) {
                        try {
                            return CompletableFuture.completedFuture(parseResponse(responseBody));
                        } catch (Exception e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    }

                    // 可重试的状态码：429（限流）、5xx（服务端错误）
                    boolean retryable = statusCode == 429 || statusCode >= 500;
                    if (retryable && retriesLeft > 0) {
                        long delayMs = computeRetryDelay(response, MAX_RETRIES - retriesLeft + 1);
                        AICompanion.LOGGER.warn("[AI] API 返回 {}，将在 {}ms 后重试（剩余 {} 次）",
                                statusCode, delayMs, retriesLeft);
                        return delay(delayMs).thenCompose(ignored ->
                                sendWithRetry(request, url, retriesLeft - 1));
                    }

                    AICompanion.LOGGER.error("[AI] API 返回 {} — URL: {}", statusCode, url);
                    return CompletableFuture.failedFuture(new RuntimeException(
                            "API 返回错误 " + statusCode + " (URL: " + url + "): " + responseBody));
                })
                .exceptionally(e -> {
                    // 网络级异常（连接超时等）也可以重试
                    if (retriesLeft > 0 && isRetryableException(e)) {
                        long delayMs = BASE_RETRY_DELAY_MS * (1L << (MAX_RETRIES - retriesLeft));
                        AICompanion.LOGGER.warn("[AI] 请求异常: {}，将在 {}ms 后重试（剩余 {} 次）",
                                e.getMessage(), delayMs, retriesLeft);
                        return delay(delayMs).thenCompose(ignored ->
                                sendWithRetry(request, url, retriesLeft - 1)).join();
                    }
                    throw e instanceof RuntimeException re ? re
                            : new RuntimeException(e);
                });
    }

    /**
     * 计算重试延迟。优先使用 429 响应中的 Retry-After header，
     * 否则使用指数退避。
     */
    private static long computeRetryDelay(HttpResponse<String> response, int attempt) {
        // 检查 Retry-After header（秒数）
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter);
                return Math.min(seconds * 1000, 30_000); // 上限 30 秒
            } catch (NumberFormatException ignored) {
                // 可能是 HTTP-date 格式，忽略并使用退避
            }
        }
        // 指数退避：1s, 2s, 4s...
        return BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
    }

    /** 判断异常是否值得重试（连接超时、网络错误等） */
    private static boolean isRetryableException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** 返回一个在指定毫秒后完成的 CompletableFuture */
    private static CompletableFuture<Void> delay(long millis) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        RETRY_SCHEDULER.schedule(() -> f.complete(null), millis, TimeUnit.MILLISECONDS);
        return f;
    }

    /**
     * 智能拼接 base URL 和 endpoint，避免重复路径段。
     * 示例：
     * <ul>
     *   <li>{@code "https://api.openai.com"} + {@code "/v1/chat/completions"} → {@code "https://api.openai.com/v1/chat/completions"}</li>
     *   <li>{@code "https://api.openai.com/v1"} + {@code "/v1/chat/completions"} → {@code "https://api.openai.com/v1/chat/completions"}</li>
     *   <li>{@code "https://my.api.com/"} + {@code "chat/completions"} → {@code "https://my.api.com/chat/completions"}</li>
     *   <li>{@code "https://my.api.com/base/"} + {@code "/v1/chat/completions"} → {@code "https://my.api.com/base/v1/chat/completions"}</li>
     * </ul>
     */
    static String buildUrl(String apiUrl, String endpoint) {
        String base = apiUrl.replaceAll("/+$", "");   // 去除末尾斜杠
        String path = endpoint;

        // 如果 base 末尾的路径段也是 endpoint 的开头，
        // 去除重复。例如 base=".../v1" + endpoint="/v1/chat/..."
        if (!path.isEmpty() && path.startsWith("/")) {
            String[] baseParts = base.split("/");
            String lastBasePart = baseParts.length > 0 ? baseParts[baseParts.length - 1] : "";
            // 检查 endpoint 是否以 "/<lastPart>/" 开头
            if (!lastBasePart.isEmpty() && path.startsWith("/" + lastBasePart + "/")) {
                path = path.substring(lastBasePart.length() + 1);  // 去除 "/<lastPart>"
            }
        }

        // 确保 base 和 path 之间恰好有一个斜杠
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        } else {
            return base + path;
        }
    }

    /**
     * 解析 API 响应 JSON 并提取 AI 的回复文本。
     * <p>
     * 尝试多种提取策略以支持不同的 API 提供商：
     * <ol>
     *   <li>OpenAI 标准格式：{@code choices[0].message.content}</li>
     *   <li>其他字段：{@code result}、{@code output}、{@code response}、{@code text}</li>
     *   <li>流式风格：{@code delta.content}</li>
     * </ol>
     */
    private static String parseResponse(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) throw new RuntimeException("Empty response");

            // 检查 API 错误
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

            // 策略 1：OpenAI 标准格式 — choices[0].message.content
            String content = extractFromChoices(obj);
            if (content != null) return content;

            // 策略 1.5：Anthropic Messages API — content 是一个 block 数组
            // e.g. [{"type":"thinking","thinking":"..."}, {"type":"text","text":"actual response"}]
            content = extractFromAnthropicContent(obj);
            if (content != null) return content;

            // 策略 2：某些提供商使用的顶层字段
            for (String field : new String[]{
                    "result", "output", "response", "text",
                    "completion", "data", "message", "content"}) {
                content = extractStringField(obj, field);
                if (content != null) return content;
            }

            // 策略 3：嵌套在 "data" 数组中（Azure 风格）
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

            // 所有策略都失败 — 记录完整响应以便调试
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

    /** 尝试从 OpenAI 风格的 choices 数组中提取内容 */
    private static String extractFromChoices(JsonObject obj) {
        if (!obj.has("choices")) return null;
        JsonElement choicesEl = obj.get("choices");
        if (!choicesEl.isJsonArray()) return null;
        JsonArray choices = choicesEl.getAsJsonArray();
        if (choices.isEmpty()) return null;

        JsonElement firstEl = choices.get(0);
        if (!firstEl.isJsonObject()) return null;
        JsonObject firstChoice = firstEl.getAsJsonObject();

        // choices[0].message.content（标准格式）
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
            // choices[0].message 为纯字符串（某些提供商）
            if (msgEl.isJsonPrimitive()) {
                return msgEl.getAsString();
            }
        }

        // choices[0].delta.content（流式风格）
        if (firstChoice.has("delta")) {
            JsonElement deltaEl = firstChoice.get("delta");
            if (deltaEl.isJsonObject()) {
                JsonObject delta = deltaEl.getAsJsonObject();
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    return delta.get("content").getAsString();
                }
            }
        }

        // choices[0].text（旧版 completions API）
        if (firstChoice.has("text")) {
            return firstChoice.get("text").getAsString();
        }

        return null;
    }

    /**
     * 从 Anthropic Messages API 格式中提取内容。
     * 响应包含一个顶层 "content" block 数组：
     * <pre>
     * {
     *   "content": [
     *     {"type": "thinking", "thinking": "..."},
     *     {"type": "text", "text": "实际响应文本"}
     *   ]
     * }
     * </pre>
     * 查找第一个 type 为 "text" 的 block 并返回其 "text" 字段。
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

            // 只提取 "text" 类型的 block，跳过 "thinking" 类型
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

    /** 尝试从顶层字段中提取字符串值 */
    private static String extractStringField(JsonObject obj, String field) {
        if (!obj.has(field)) return null;
        JsonElement el = obj.get(field);
        if (el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        // 如果是对象，尝试从中获取 "content" 或 "text"
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
