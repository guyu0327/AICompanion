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

        BUILDER.pop();

        // -- 行为设置 --
        BUILDER.comment("AI 行为设置")
               .push("behavior");

        SYSTEM_PROMPT = BUILDER
                .comment("定义 AI 同伴个性和行为的 system prompt")
                .define("systemPrompt", "You are a helpful and friendly AI companion in Minecraft.");

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
