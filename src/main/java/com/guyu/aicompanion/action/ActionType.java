package com.guyu.aicompanion.action;

/**
 * AI 同伴可以执行的所有动作类型
 */
public enum ActionType {
    MOVE("移动"),
    MINE("挖掘"),
    ATTACK("攻击"),
    USE_ITEM("使用物品"),
    EAT("进食"),
    CHAT("聊天"),
    WAIT("等待"),
    SLEEP("睡觉"),
    WAKE_UP("醒来"),
    DROP_ITEM("丢弃物品"),
    PLACE_BLOCK("放置方块");

    private final String displayName;

    ActionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
