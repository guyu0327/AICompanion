package com.guyu.aicompanion.ai;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 维护 AI 同伴的滚动聊天历史。
 * 用于向 AI 提供对话上下文，以及记录同伴说过的话
 * （通过 ActionExecutor 广播）。
 */
public class ChatHistory {

    private static final int MAX_HISTORY = 20;

    public record Entry(String sender, String message) {}

    private final LinkedList<Entry> history = new LinkedList<>();
    private final String companionName;

    public ChatHistory(String companionName) {
        this.companionName = companionName;
    }

    /** 将消息添加到历史记录中 */
    public void add(String sender, String message) {
        if (message == null || message.isBlank()) return;
        history.addLast(new Entry(sender, message.trim()));
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    /**
     * 将历史记录格式化为 API 的消息对象列表。
     * 同伴自身的消息标记为 assistant，其他发送者的消息标记为 user。
     */
    public List<AIService.Message> toApiMessages() {
        List<AIService.Message> msgs = new ArrayList<>();
        for (Entry e : history) {
            String role = e.sender().equals(companionName) ? "assistant" : "user";
            msgs.add(new AIService.Message(role, e.sender() + ": " + e.message()));
        }
        return msgs;
    }

    /** 将历史记录格式化为提示词用的可读字符串 */
    public String toFormattedString() {
        if (history.isEmpty()) return "（暂无对话记录）";
        StringBuilder sb = new StringBuilder();
        for (Entry e : history) {
            sb.append("[").append(e.sender).append("] ").append(e.message).append("\n");
        }
        return sb.toString().trim();
    }

    public void clear() {
        history.clear();
    }

    public int size() {
        return history.size();
    }
}
