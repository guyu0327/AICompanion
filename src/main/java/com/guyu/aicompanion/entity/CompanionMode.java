package com.guyu.aicompanion.entity;

/**
 * AI 同伴的行为模式。
 * <ul>
 *   <li>{@link #FOLLOW} — 跟随拥有者（默认）</li>
 *   <li>{@link #STAND} — 原地待命（仍反击 + 自动拾取）</li>
 *   <li>{@link #FREE} — AI 完全自主决策</li>
 * </ul>
 */
public enum CompanionMode {
    FOLLOW("跟随"),
    STAND("待命"),
    FREE("自由");

    private final String displayName;

    CompanionMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 获取下一个模式（循环：FOLLOW → STAND → FREE → FOLLOW） */
    public CompanionMode next() {
        CompanionMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
