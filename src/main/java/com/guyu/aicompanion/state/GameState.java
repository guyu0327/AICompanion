package com.guyu.aicompanion.state;

import com.guyu.aicompanion.ai.ChatHistory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the current game state around an AI companion and serializes it to JSON.
 * This JSON is sent to the AI as the "user message" so it can make informed decisions.
 */
public class GameState {

    private static final int BLOCK_SCAN_RADIUS = 5;
    private static final int ENTITY_SCAN_RANGE = 24;

    /**
     * Collect all relevant game state for the given companion.
     */
    public static JsonObject collect(Mob companion, ChatHistory chatHistory) {
        ServerLevel level = (ServerLevel) companion.level();
        BlockPos pos = companion.blockPosition();

        JsonObject state = new JsonObject();

        // Position
        state.addProperty("x", pos.getX());
        state.addProperty("y", pos.getY());
        state.addProperty("z", pos.getZ());

        // Dimension
        String dimId = level.dimension().identifier().getPath();
        state.addProperty("dimension", dimId);

        // Biome
        String biome = level.getBiome(pos).unwrapKey()
                .map(k -> k.identifier().getPath())
                .orElse("unknown");
        state.addProperty("biome", biome);

        // Time of day
        long dayTime = level.getOverworldClockTime() % 24000;
        state.addProperty("timeOfDay", dayTime);
        state.addProperty("timeOfDayStr", formatTime(dayTime));

        // Weather
        state.addProperty("raining", level.isRaining());
        state.addProperty("thundering", level.isThundering());

        // Health
        state.addProperty("health", Math.round(companion.getHealth()));
        state.addProperty("maxHealth", Math.round(companion.getMaxHealth()));

        // Held item
        ItemStack held = companion.getMainHandItem();
        if (held.isEmpty()) {
            state.addProperty("heldItem", "empty");
        } else {
            state.addProperty("heldItem", held.getItem().builtInRegistryHolder()
                    .key().identifier().getPath());
            state.addProperty("heldItemCount", held.getCount());
        }

        // Nearby blocks (summarized by type)
        state.add("nearbyBlocks", scanBlocks(level, pos));

        // Nearby entities
        state.add("nearbyEntities", scanEntities(companion, level));

        // Chat history
        if (chatHistory != null) {
            state.addProperty("chatHistory", chatHistory.toFormattedString());
        }

        return state;
    }

    /** Scan blocks in a radius and return counts grouped by block type. */
    private static JsonObject scanBlocks(ServerLevel level, BlockPos center) {
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        BlockPos.betweenClosedStream(
                center.offset(-BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS),
                center.offset(BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS)
        ).forEach(bp -> {
            BlockState bs = level.getBlockState(bp);
            if (bs.isAir()) return;
            String name = bs.getBlock().builtInRegistryHolder()
                    .key().identifier().getPath();
            blockCounts.merge(name, 1, Integer::sum);
        });
        JsonObject obj = new JsonObject();
        blockCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(15)
                .forEach(e -> obj.addProperty(e.getKey(), e.getValue()));
        return obj;
    }

    /** List nearby entities with type and distance. */
    private static JsonArray scanEntities(Mob companion, ServerLevel level) {
        AABB box = companion.getBoundingBox().inflate(ENTITY_SCAN_RANGE);
        List<Entity> entities = level.getEntities((Entity) null, box, e -> true);
        JsonArray arr = new JsonArray();
        entities.stream()
                .filter(e -> e != companion)
                .sorted((a, b) -> Double.compare(
                        companion.distanceToSqr(a), companion.distanceToSqr(b)))
                .limit(10)
                .forEach(e -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("type", e.getType().builtInRegistryHolder()
                            .key().identifier().getPath());
                    obj.addProperty("name", e.getName().getString());
                    obj.addProperty("distance",
                            Math.round(companion.distanceTo(e) * 10.0) / 10.0);
                    if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                        obj.addProperty("health", Math.round(le.getHealth()));
                        obj.addProperty("maxHealth", Math.round(le.getMaxHealth()));
                    }
                    arr.add(obj);
                });
        return arr;
    }

    /** Convert Minecraft day-time ticks to a human-readable string. */
    private static String formatTime(long dayTime) {
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }
}
