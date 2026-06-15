package com.guyu.aicompanion;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // -- API Settings --
    public static final ModConfigSpec.ConfigValue<String> API_URL;
    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.ConfigValue<String> API_ENDPOINT;
    public static final ModConfigSpec.IntValue API_TIMEOUT;

    // -- Model Settings --
    public static final ModConfigSpec.ConfigValue<String> MODEL_NAME;
    public static final ModConfigSpec.DoubleValue TEMPERATURE;
    public static final ModConfigSpec.IntValue MAX_TOKENS;

    // -- Behavior Settings --
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_PROMPT;

    public static final ModConfigSpec SPEC;

    static {
        // -- API Settings --
        BUILDER.comment("API connection settings")
               .push("api");

        API_URL = BUILDER
                .comment("The base URL of the AI API (e.g. https://api.openai.com)")
                .define("apiUrl", "https://api.openai.com");

        API_KEY = BUILDER
                .comment("Your API key for authentication. Keep this secret!")
                .define("apiKey", "");

        API_ENDPOINT = BUILDER
                .comment("The chat completions endpoint path appended to the API URL")
                .define("endpoint", "/v1/chat/completions");

        API_TIMEOUT = BUILDER
                .comment("HTTP request timeout in seconds")
                .defineInRange("timeout", 30, 1, 300);

        BUILDER.pop();

        // -- Model Settings --
        BUILDER.comment("AI model settings")
               .push("model");

        MODEL_NAME = BUILDER
                .comment("The model identifier to use for chat completions")
                .define("modelName", "gpt-3.5-turbo");

        TEMPERATURE = BUILDER
                .comment("Sampling temperature. 0.0 = deterministic, 2.0 = most random")
                .defineInRange("temperature", 0.7, 0.0, 2.0);

        MAX_TOKENS = BUILDER
                .comment("Maximum number of tokens to generate in the response")
                .defineInRange("maxTokens", 1024, 1, 128000);

        BUILDER.pop();

        // -- Behavior Settings --
        BUILDER.comment("AI behavior settings")
               .push("behavior");

        SYSTEM_PROMPT = BUILDER
                .comment("The system prompt that defines the AI companion personality and behavior")
                .define("systemPrompt", "You are a helpful and friendly AI companion in Minecraft.");

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
