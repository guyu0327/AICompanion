package com.guyu.aicompanion.ai;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Maintains a rolling chat history for an AI companion.
 * Used both to feed conversation context to the AI and to record
 * what the companion has said (via ActionExecutor broadcasts).
 */
public class ChatHistory {

    private static final int MAX_HISTORY = 20;

    public record Entry(String sender, String message) {}

    private final LinkedList<Entry> history = new LinkedList<>();

    /** Add a message to the history. */
    public void add(String sender, String message) {
        if (message == null || message.isBlank()) return;
        history.addLast(new Entry(sender, message.trim()));
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    /** Format history as a list of message objects for the API. */
    public List<AIService.Message> toApiMessages() {
        List<AIService.Message> msgs = new ArrayList<>();
        for (Entry e : history) {
            msgs.add(new AIService.Message("user", e.sender + ": " + e.message));
        }
        return msgs;
    }

    /** Format history as a human-readable string for the prompt. */
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
