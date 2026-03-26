package day25;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Day 25: 会话上下文管理器
 *
 * 解决的问题：
 * 1. 序列化：ChatMessage 多态反序列化（LangChain4j 内置 Codec 处理）
 * 2. 内存控制：写入 Redis 前先截断，防止单条 Redis value 过大
 * 3. 会话生命周期：结束时清理内存引用，让 GC 回收
 * 4. 懒加载：从 Redis 按需加载，不全量预热到堆内存
 *
 * 架构：
 *   堆内存（短暂）：activeContexts（当前活跃会话）
 *   Redis（持久）：历史序列化 JSON
 *
 * 注意：本实现用 Map 模拟 Redis，生产中替换为 Jedis/Lettuce 客户端即可
 */
public class SessionContextManager {

    /** 单条 Redis value 最大消息条数，超出则截断旧消息 */
    private static final int MAX_MESSAGES_PER_SESSION = 50;

    /**
     * 模拟 Redis（生产中替换为 RedisClient）
     * key: sessionId, value: JSON 字符串
     */
    private final Map<String, String> redisStore = new ConcurrentHashMap<>();

    /**
     * 活跃会话的堆内存缓存（请求处理期间使用）
     * 请求结束后必须调用 closeSession() 清理，否则内存泄漏
     */
    private final Map<String, List<ChatMessage>> activeContexts = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────
    // 对外 API
    // ──────────────────────────────────────────────

    /**
     * 加载会话（懒加载：仅当请求到来时才从 Redis 读入堆）
     */
    public List<ChatMessage> loadSession(String sessionId) {
        // 1. 堆内存已有（同一请求内多次调用）
        if (activeContexts.containsKey(sessionId)) {
            return activeContexts.get(sessionId);
        }

        // 2. 从 Redis 反序列化（多态子类由 LangChain4j Codec 自动处理）
        String json = redisStore.get(sessionId);
        List<ChatMessage> messages = (json != null && !json.isBlank())
                ? new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json))
                : new ArrayList<>();

        activeContexts.put(sessionId, messages);
        System.out.printf("[SessionMgr] 加载会话 %s，%d 条消息%n", sessionId, messages.size());
        return messages;
    }

    /**
     * 追加消息（只写堆内存，请求结束时统一 flush 到 Redis）
     */
    public void addMessage(String sessionId, ChatMessage message) {
        loadSession(sessionId).add(message);
    }

    /**
     * 请求结束：持久化到 Redis + 清理堆内存引用
     *
     * 必须在 finally 块中调用，防止内存泄漏
     */
    public void closeSession(String sessionId) {
        List<ChatMessage> messages = activeContexts.remove(sessionId); // 从堆内存移除
        if (messages == null || messages.isEmpty()) return;

        // 截断：只保留最近 MAX_MESSAGES_PER_SESSION 条，防止 Redis value 无限增长
        List<ChatMessage> toSave = truncate(messages);

        // 序列化（LangChain4j 自动在 JSON 里注入 type 字段，反序列化时还原子类）
        String json = ChatMessageSerializer.messagesToJson(toSave);
        redisStore.put(sessionId, json);

        System.out.printf("[SessionMgr] 会话 %s 已持久化（%d 条），堆内存引用已释放%n",
                sessionId, toSave.size());
    }

    /**
     * 会话彻底结束（用户登出/超时）：清除 Redis 数据
     */
    public void deleteSession(String sessionId) {
        activeContexts.remove(sessionId);
        redisStore.remove(sessionId);
        System.out.printf("[SessionMgr] 会话 %s 已删除%n", sessionId);
    }

    // ──────────────────────────────────────────────
    // 内部工具
    // ──────────────────────────────────────────────

    /**
     * 截断策略：保留最新的 N 条（FIFO，丢弃最旧的）
     * 生产中可改为：先摘要旧消息，再保留摘要 + 新消息
     */
    private List<ChatMessage> truncate(List<ChatMessage> messages) {
        if (messages.size() <= MAX_MESSAGES_PER_SESSION) return messages;
        int dropCount = messages.size() - MAX_MESSAGES_PER_SESSION;
        System.out.printf("[SessionMgr] 截断 %d 条旧消息%n", dropCount);
        return new ArrayList<>(messages.subList(dropCount, messages.size()));
    }

    /** 查看当前堆内存中活跃会话数（用于监控） */
    public int activeSessionCount() {
        return activeContexts.size();
    }

    /** 查看 Redis 中持久化的会话数（用于监控） */
    public int persistedSessionCount() {
        return redisStore.size();
    }
}
