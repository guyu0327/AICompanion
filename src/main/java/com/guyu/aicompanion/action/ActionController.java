package com.guyu.aicompanion.action;

import com.guyu.aicompanion.ai.ChatHistory;
import com.guyu.aicompanion.entity.AICompanionEntity;

/**
 * 动作控制器的抽象基类。
 * <p>
 * 持有对所有控制器都需要的共享引用：同伴实体、聊天历史、
 * 以及用于回调执行器的引用（设置状态、完成动作、播报消息）。
 * <p>
 * 子类负责特定类别的动作逻辑（移动、挖掘、战斗、物品）。
 */
abstract class ActionController {

    protected final AICompanionEntity companion;
    protected final ChatHistory chatHistory;
    protected final ActionExecutor executor;

    ActionController(AICompanionEntity companion, ChatHistory chatHistory, ActionExecutor executor) {
        this.companion = companion;
        this.chatHistory = chatHistory;
        this.executor = executor;
    }
}
