package day18;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 核心接口
 *
 * 所有 Agent（包括 Orchestrator 和 SubAgent）都必须实现此接口
 *
 * 设计原则：
 * 1. 统一接口：Orchestrator 和 SubAgent 使用相同的接口，便于组合
 * 2. 异步支持：返回 CompletableFuture，支持非阻塞调用
 * 3. 自描述：getName() 和 getDescription() 让 Orchestrator 知道何时调用该 Agent
 */
public interface Agent {

    /**
     * Agent 的唯一标识名称
     * 例如："RAG Agent", "Data Agent", "Report Agent"
     */
    String getName();

    /**
     * Agent 的能力描述
     * 用于 Orchestrator 决策：什么任务应该分发给这个 Agent
     *
     * 示例：
     * "负责查询知识库，回答基于文档的问题。擅长处理公司政策、制度、流程等相关问题。"
     * "负责数据分析，可以处理销售数据、统计、趋势计算等任务。"
     */
    String getDescription();

    /**
     * 处理消息并返回响应
     *
     * @param message 输入消息
     * @return 响应消息（同步）
     */
    AgentMessage handle(AgentMessage message);

    /**
     * 处理消息并返回响应
     *
     * @param message 输入消息
     * @return 响应消息（同步）
     */
    default AgentMessage safeHandle(AgentMessage message) {
        checkCallChain(message);
        return handle(message);
    }

    /**
     * 异步处理消息（可选实现）
     *
     * 默认实现：同步调用转异步
     * 子类可以重写以实现真正的异步处理
     */
    default CompletableFuture<AgentMessage> handleAsync(AgentMessage message) {
        return CompletableFuture.completedFuture(handle(message));
    }

    int MAX_DEPTH = 5;

    default void checkCallChain(AgentMessage message) {
        // 1. 深度检测
        if (message.getDepth() > MAX_DEPTH) {
            throw new RuntimeException("调用深度过深, 最大深度:" + MAX_DEPTH);
        }
        // 2. 环检测（callChain 里有重复的 Agent 名）
        if (message.getCallChain().contains(getName())) {
            throw new RuntimeException("检测到循环调用agent,调用链路:"+ message.getCallChain());
        }
        // 超出则抛异常，message 里有 depth 和 callChain
    }

    /**
     * 初始化（可选）
     * 如果 Agent 需要初始化资源（如连接数据库），可以重写此方法
     */
    default void init() {
        // 空实现
    }

    /**
     * 销毁（可选）
     * 如果 Agent 持有需要清理的资源，可以重写此方法
     */
    default void destroy() {
        // 空实现
    }
}
