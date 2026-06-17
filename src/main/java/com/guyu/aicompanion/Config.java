package com.guyu.aicompanion;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // -- API 设置 --
    public static final ModConfigSpec.ConfigValue<String> API_URL;
    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.ConfigValue<String> API_ENDPOINT;
    public static final ModConfigSpec.IntValue API_TIMEOUT;

    // -- 模型设置 --
    public static final ModConfigSpec.ConfigValue<String> MODEL_NAME;
    public static final ModConfigSpec.DoubleValue TEMPERATURE;
    public static final ModConfigSpec.IntValue MAX_TOKENS;
    public static final ModConfigSpec.BooleanValue ENABLE_JSON_MODE;

    // -- 行为设置 --
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_PROMPT;

    public static final ModConfigSpec SPEC;

    static {
        // -- API 设置 --
        BUILDER.comment("API 连接设置")
               .push("api");

        API_URL = BUILDER
                .comment("AI API 的基础 URL（例如 https://api.openai.com）")
                .define("apiUrl", "https://api.openai.com");

        API_KEY = BUILDER
                .comment("API 认证密钥，请妥善保管！")
                .define("apiKey", "");

        API_ENDPOINT = BUILDER
                .comment("Chat completions 接口的路径，会追加到 API URL 后面")
                .define("endpoint", "/v1/chat/completions");

        API_TIMEOUT = BUILDER
                .comment("HTTP 请求超时时间（秒）")
                .defineInRange("timeout", 30, 1, 300);

        BUILDER.pop();

        // -- 模型设置 --
        BUILDER.comment("AI 模型设置")
               .push("model");

        MODEL_NAME = BUILDER
                .comment("用于 chat completions 的模型标识符")
                .define("modelName", "gpt-3.5-turbo");

        TEMPERATURE = BUILDER
                .comment("采样温度。0.0 = 确定性输出，2.0 = 最随机")
                .defineInRange("temperature", 0.7, 0.0, 2.0);

        MAX_TOKENS = BUILDER
                .comment("响应中生成的最大 token 数")
                .defineInRange("maxTokens", 1024, 1, 128000);

        ENABLE_JSON_MODE = BUILDER
                .comment("是否启用 JSON 模式（response_format: json_object），要求 AI 返回纯 JSON。"
                        + "部分 API（如 Anthropic）不支持此参数，设为 false 可关闭。")
                .define("enableJsonMode", true);

        BUILDER.pop();

        // -- 行为设置 --
        BUILDER.comment("AI 行为设置")
               .push("behavior");

        SYSTEM_PROMPT = BUILDER
                .comment("定义 AI 同伴个性和行为的 system prompt")
                .define("systemPrompt", "你是一个友好且乐于助人的 Minecraft AI 同伴。");

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
