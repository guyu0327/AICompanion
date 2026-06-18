package com.guyu.aicompanion.state;

import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集 AI 同伴周围当前的游戏状态并序列化为 JSON。
 * 此 JSON 作为"user message"发送给 AI，以便做出有依据的决策。
 */
public class GameState {

    private static final int BLOCK_SCAN_RADIUS = 8;
    private static final int ENTITY_SCAN_RANGE = 32;
    private static final int MAX_BLOCK_TYPES = 15;
    private static final int NEAREST_PER_TYPE = 3;
    private static final int MAX_ENTITIES = 15;

    /**
     * 收集指定同伴的所有相关游戏状态。
     */
    public static JsonObject collect(AICompanionEntity companion, ChatHistory chatHistory) {
        ServerLevel level = (ServerLevel) companion.level();
        BlockPos pos = companion.blockPosition();

        JsonObject state = new JsonObject();

        // 位置
        state.addProperty("x", pos.getX());
        state.addProperty("y", pos.getY());
        state.addProperty("z", pos.getZ());

        // 维度
        String dimId = level.dimension().identifier().getPath();
        state.addProperty("dimension", dimId);

        // 生物群系
        String biome = level.getBiome(pos).unwrapKey()
                .map(k -> k.identifier().getPath())
                .orElse("unknown");
        state.addProperty("biome", biome);

        // 一天中的时间
        long dayTime = level.getOverworldClockTime() % 24000;
        state.addProperty("timeOfDay", dayTime);
        state.addProperty("timeOfDayStr", formatTime(dayTime));

        // 天气
        state.addProperty("raining", level.isRaining());
        state.addProperty("thundering", level.isThundering());

        // 生命值
        state.addProperty("health", Math.round(companion.getHealth()));
        state.addProperty("maxHealth", Math.round(companion.getMaxHealth()));

        // 手持物品
        ItemStack held = companion.getMainHandItem();
        if (held.isEmpty()) {
            state.addProperty("heldItem", "empty");
        } else {
            state.addProperty("heldItem", held.getItem().builtInRegistryHolder()
                    .key().identifier().getPath());
            state.addProperty("heldItemCount", held.getCount());
        }

        // 背包
        state.add("inventory", scanInventory(companion.getInventory()));
        state.addProperty("hunger", companion.getHunger());
        state.addProperty("maxHunger", companion.getMaxHunger());
        state.addProperty("saturation", Math.round(companion.getSaturation() * 10.0) / 10.0);
        state.addProperty("mode", companion.getMode().name());
        int freeSlots = 0;
        for (int i = 0; i < companion.getInventory().getContainerSize(); i++) {
            if (companion.getInventory().getItem(i).isEmpty()) freeSlots++;
        }
        state.addProperty("inventoryFreeSlots", freeSlots);
        state.addProperty("inventoryTotalSlots", companion.getInventory().getContainerSize());

        // 附近方块（按类型汇总）
        state.add("nearbyBlocks", scanBlocks(level, pos));

        // 附近实体
        state.add("nearbyEntities", scanEntities(companion, level));

        // 聊天历史
        if (chatHistory != null) {
            state.addProperty("chatHistory", chatHistory.toFormattedString());
        }

        return state;
    }

    /**
     * 扫描半径内的方块。对每种方块类型，返回总数量和
     * 最近几个的坐标 — 让 AI 确切知道去哪里移动/挖掘。
     */
    private static JsonObject scanBlocks(ServerLevel level, BlockPos center) {
        // 收集所有非空气方块及其坐标
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

        // 构建输出：每种类型 → 数量 + 最近的坐标
        JsonObject obj = new JsonObject();
        blocksByName.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(MAX_BLOCK_TYPES)
                .forEach(entry -> {
                    String name = entry.getKey();
                    List<BlockPos> positions = entry.getValue();

                    // 按到同伴的距离排序，取最近的 N 个
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

    /** 列出附近实体，包含类型、坐标、距离和生命值 */
    private static JsonArray scanEntities(AICompanionEntity companion, ServerLevel level) {
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

                    // 坐标
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

    /** 扫描同伴背包并返回 JSON 数组格式的摘要 */
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

    /** 将 Minecraft 的 day-time ticks 转换为可读字符串 */
    private static String formatTime(long dayTime) {
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }
}
