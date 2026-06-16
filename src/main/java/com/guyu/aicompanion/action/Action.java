package com.guyu.aicompanion.action;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.guyu.aicompanion.AICompanion;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Represents a single action the AI companion can execute.
 * <p>
 * Fields are populated depending on the action type:
 * <ul>
 *   <li>MOVE / MINE / PLACE_BLOCK → targetPos</li>
 *   <li>ATTACK → targetEntityId (+ optional targetEntityName)</li>
 *   <li>CHAT → message</li>
 *   <li>WAIT → (no extra fields, duration controlled by executor)</li>
 *   <li>EAT / USE_ITEM / DROP_ITEM / SLEEP → (no extra fields)</li>
 * </ul>
 */
public class Action {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ActionType type;
    private final BlockPos targetPos;
    private final UUID targetEntityId;
    private final String targetEntityName;
    private final String message;
    private final String reason;

    public Action(ActionType type, BlockPos targetPos, UUID targetEntityId,
                  String targetEntityName, String message, String reason) {
        this.type = type;
        this.targetPos = targetPos;
        this.targetEntityId = targetEntityId;
        this.targetEntityName = targetEntityName;
        this.message = message;
        this.reason = reason;
    }

    // ── Static factories ────────────────────────────────────────────────────

    /** Simple action with no extra parameters (WAIT, EAT, SLEEP, etc.) */
    public static Action simple(ActionType type, String reason) {
        return new Action(type, null, null, null, null, reason);
    }

    /** Move to a block position. */
    public static Action move(BlockPos pos, String reason) {
        return new Action(ActionType.MOVE, pos, null, null, null, reason);
    }

    /** Mine a block at the given position. */
    public static Action mine(BlockPos pos, String reason) {
        return new Action(ActionType.MINE, pos, null, null, null, reason);
    }

    /** Attack the nearest entity of the given type within searchRange blocks. */
    public static Action attack(String entityName, int searchRange, String reason) {
        return new Action(ActionType.ATTACK, null, null, entityName, null, reason);
    }

    /** Send a chat message to nearby players. */
    public static Action chat(String message, String reason) {
        return new Action(ActionType.CHAT, null, null, null, message, reason);
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public ActionType getType() { return type; }
    public BlockPos getTargetPos() { return targetPos; }
    public UUID getTargetEntityId() { return targetEntityId; }
    public String getTargetEntityName() { return targetEntityName; }
    public String getMessage() { return message; }
    public String getReason() { return reason; }

    // ── JSON serialization ──────────────────────────────────────────────────

    public String toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("action", type.name().toLowerCase());
        if (targetPos != null) obj.add("target", posToArray(targetPos));
        if (targetEntityId != null) obj.addProperty("targetEntity", targetEntityId.toString());
        if (targetEntityName != null) obj.addProperty("targetName", targetEntityName);
        if (message != null) obj.addProperty("message", message);
        if (reason != null) obj.addProperty("reason", reason);
        return GSON.toJson(obj);
    }

    /**
     * Parse an Action from AI JSON output.
     * Expected format:
     * <pre>
     * {
     *   "action": "move|mine|attack|chat|wait|eat|sleep|drop_item|use_item|place_block",
     *   "target": [x, y, z],         // optional, for MOVE/MINE/PLACE_BLOCK
     *   "targetName": "zombie",       // optional, for ATTACK
     *   "message": "hello",           // optional, for CHAT
     *   "reason": "going to mine"     // optional, for logging
     * }
     * </pre>
     */
    public static Action fromJson(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) return simple(ActionType.WAIT, "Failed to parse null JSON");

            String actionStr = obj.has("action") ? obj.get("action").getAsString() : "wait";
            ActionType type = parseActionType(actionStr);

            BlockPos pos = obj.has("target") ? parsePos(obj.get("target")) : null;
            String targetName = obj.has("targetName") ? obj.get("targetName").getAsString() : null;
            String message = obj.has("message") ? obj.get("message").getAsString() : null;
            String reason = obj.has("reason") ? obj.get("reason").getAsString() : null;

            return new Action(type, pos, null, targetName, message, reason);
        } catch (Exception e) {
            AICompanion.LOGGER.warn("Failed to parse Action JSON: {}", json, e);
            return simple(ActionType.WAIT, "JSON parse error: " + e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ActionType parseActionType(String name) {
        return switch (name.toLowerCase()) {
            case "move"        -> ActionType.MOVE;
            case "mine"        -> ActionType.MINE;
            case "attack"      -> ActionType.ATTACK;
            case "use_item"    -> ActionType.USE_ITEM;
            case "eat"         -> ActionType.EAT;
            case "chat"        -> ActionType.CHAT;
            case "wait"        -> ActionType.WAIT;
            case "sleep"       -> ActionType.SLEEP;
            case "wake_up"     -> ActionType.WAKE_UP;
            case "drop_item"   -> ActionType.DROP_ITEM;
            case "place_block" -> ActionType.PLACE_BLOCK;
            default            -> ActionType.WAIT;
        };
    }

    private static BlockPos parsePos(JsonElement element) {
        if (!element.isJsonArray()) return null;
        JsonArray arr = element.getAsJsonArray();
        if (arr.size() < 3) return null;
        return new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }

    private static JsonArray posToArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Action{").append(type.name());
        if (targetPos != null) sb.append(" pos=").append(targetPos.toShortString());
        if (targetEntityName != null) sb.append(" target=").append(targetEntityName);
        if (message != null) sb.append(" msg=\"").append(message).append("\"");
        if (reason != null) sb.append(" reason=\"").append(reason).append("\"");
        return sb.append("}").toString();
    }
}
