package com.guyu.aicompanion.state;

import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the current game state around an AI companion and serializes it to JSON.
 * This JSON is sent to the AI as the "user message" so it can make informed decisions.
 */
public class GameState {

    private static final int BLOCK_SCAN_RADIUS = 8;
    private static final int ENTITY_SCAN_RANGE = 32;
    private static final int MAX_BLOCK_TYPES = 15;
    private static final int NEAREST_PER_TYPE = 3;
    private static final int MAX_ENTITIES = 15;

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

        // Inventory
        if (companion instanceof AICompanionEntity ace) {
            state.add("inventory", scanInventory(ace.getInventory()));
            state.addProperty("hunger", ace.getHunger());
            state.addProperty("maxHunger", ace.getMaxHunger());
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

    /**
     * Scan blocks in a radius.  For each block type, return the total count
     * and the positions of the nearest examples — so the AI knows exactly
     * where to move/mine.
     */
    private static JsonObject scanBlocks(ServerLevel level, BlockPos center) {
        // Collect all non-air blocks with their positions
        Map<String, List<BlockPos>> blocksByName = new LinkedHashMap<>();
        BlockPos.betweenClosedStream(
                center.offset(-BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS, -BLOCK_SCAN_RADIUS),
                center.offset(BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS, BLOCK_SCAN_RADIUS)
        ).forEach(bp -> {
            BlockState bs = level.getBlockState(bp);
            if (bs.isAir()) return;
            String name = bs.getBlock().builtInRegistryHolder()
                    .key().identifier().getPath();
            blocksByName.computeIfAbsent(name, k -> new ArrayList<>()).add(bp);
        });

        // Build the output: for each type → count + nearest positions
        JsonObject obj = new JsonObject();
        blocksByName.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(MAX_BLOCK_TYPES)
                .forEach(entry -> {
                    String name = entry.getKey();
                    List<BlockPos> positions = entry.getValue();

                    // Sort by distance to companion, take nearest N
                    positions.sort(Comparator.comparingDouble(
                            p -> center.distSqr(p)));

                    JsonObject blockInfo = new JsonObject();
                    blockInfo.addProperty("count", positions.size());

                    JsonArray nearestArr = new JsonArray();
                    positions.stream()
                            .limit(NEAREST_PER_TYPE)
                            .forEach(p -> {
                                JsonArray posArr = new JsonArray();
                                posArr.add(p.getX());
                                posArr.add(p.getY());
                                posArr.add(p.getZ());
                                nearestArr.add(posArr);
                            });
                    blockInfo.add("nearest", nearestArr);

                    obj.add(name, blockInfo);
                });
        return obj;
    }

    /** List nearby entities with type, position, distance and health. */
    private static JsonArray scanEntities(Mob companion, ServerLevel level) {
        AABB box = companion.getBoundingBox().inflate(ENTITY_SCAN_RANGE);
        List<Entity> entities = level.getEntities((Entity) null, box, e -> true);
        JsonArray arr = new JsonArray();
        entities.stream()
                .filter(e -> e != companion)
                .sorted((a, b) -> Double.compare(
                        companion.distanceToSqr(a), companion.distanceToSqr(b)))
                .limit(MAX_ENTITIES)
                .forEach(e -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("type", e.getType().builtInRegistryHolder()
                            .key().identifier().getPath());
                    obj.addProperty("name", e.getName().getString());

                    // Position
                    BlockPos ePos = e.blockPosition();
                    JsonArray posArr = new JsonArray();
                    posArr.add(ePos.getX());
                    posArr.add(ePos.getY());
                    posArr.add(ePos.getZ());
                    obj.add("pos", posArr);

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

    /** Scan companion inventory and return a summary as JSON array. */
    private static JsonArray scanInventory(SimpleContainer inventory) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("slot", i);
                obj.addProperty("item", stack.getItem().builtInRegistryHolder()
                        .key().identifier().getPath());
                obj.addProperty("count", stack.getCount());
                obj.addProperty("maxCount", stack.getMaxStackSize());
                arr.add(obj);
            }
        }
        return arr;
    }

    /** Convert Minecraft day-time ticks to a human-readable string. */
    private static String formatTime(long dayTime) {
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }
}
