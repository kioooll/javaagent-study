package day16;

import java.util.HashMap;
import java.util.Map;

/**
 * Day 16: Multi-Agent 消息协议
 *
 * Agent 之间通信的数据结构，类似于 "合同"
 *
 * 设计原则：
 * 1. 自包含：携带足够的上下文，接收方无需额外查询
 * 2. 可追溯：包含调用链信息，方便调试
 * 3. 可扩展：使用 Map 存储动态数据
 */
public class AgentMessage {

    /** 消息 ID（用于追踪） */
    private final String messageId;

    /** 来源 Agent 名称 */
    private final String source;

    /** 目标 Agent 名称（null 表示广播或最终输出） */
    private final String target;

    /** 消息类型 */
    private final MessageType type;

    /** 消息体（实际内容） */
    private final String content;

    /** 附加数据（键值对） */
    private final Map<String, Object> metadata;

    public AgentMessage(String source, String target, MessageType type, String content) {
        this.messageId = generateId();
        this.source = source;
        this.target = target;
        this.type = type;
        this.content = content;
        this.metadata = new HashMap<>();
    }

    public AgentMessage(String source, String target, MessageType type, String content, Map<String, Object> metadata) {
        this.messageId = generateId();
        this.source = source;
        this.target = target;
        this.type = type;
        this.content = content;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    private String generateId() {
        return "msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    // ==================== Getters ====================

    public String getMessageId() {
        return messageId;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public MessageType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 获取元数据中的值
     */
    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key, T defaultValue) {
        return (T) metadata.getOrDefault(key, defaultValue);
    }

    /**
     * 设置元数据值
     */
    public void setMeta(String key, Object value) {
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return String.format("AgentMessage{id=%s, from=%s, to=%s, type=%s, content=%s}",
                messageId, source, target, type, content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content);
    }

    /**
     * 创建回复消息
     */
    public AgentMessage reply(MessageType type, String content) {
        AgentMessage reply = new AgentMessage(this.target, this.source, type, content);
        reply.setMeta("inReplyTo", this.messageId);
        return reply;
    }

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        /** 用户请求 */
        USER_REQUEST,

        /** 任务分发（Orchestrator → SubAgent） */
        TASK_DISPATCH,

        /** 任务执行中（SubAgent → Orchestrator，可选） */
        TASK_IN_PROGRESS,

        /** 任务结果（SubAgent → Orchestrator） */
        TASK_RESULT,

        /** 任务失败 */
        TASK_ERROR,

        /** 最终响应（Orchestrator → User） */
        FINAL_RESPONSE,

        /** Agent 间协作请求（SubAgent → SubAgent） */
        AGENT_COLLABORATION
    }
}
