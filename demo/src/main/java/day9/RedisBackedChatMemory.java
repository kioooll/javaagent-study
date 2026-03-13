package day9;

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.dashscope.QwenTokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的短期记忆实现（生产级）
 *
 * 解决单机 TokenWindowChatMemory 的问题：
 * - 多实例部署时记忆丢失
 * - 重启后记忆清空
 * - 无法水平扩展
 *
 * Redis 方案（按生产环境 Key 命名规则）：
 * - Key: wencairobot:user_dialog:{user_id}:{session_id}:
 * - Type: List（有序，支持 range 查询）
 * - TTL: 30 分钟无操作自动过期
 *
 * 配套组件：
 * - 用户画像：wencairobot:user_profile:{user_id}: (Hash，长期存储)
 * - 对话日志：Kafka → MySQL (永久存储，合规审计)
 */
public class RedisBackedChatMemory implements ChatMemory {

    private final String userId;
    private final String sessionId;
    private final String redisKeyPrefix;
    private final RedisMock redis;
    private final int maxTokens;
    private final Tokenizer tokenizer;

    /**
     * @param userId 用户 ID（服务端生成，用于用户维度画像存储）
     * @param sessionId 会话 ID（服务端生成，防止信息泄漏）
     * @param maxTokens 最大 token 数
     */
    public RedisBackedChatMemory(String userId, String sessionId, int maxTokens) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.maxTokens = maxTokens;
        // 生产环境 Key 命名：wencairobot:user_dialog:{user_id}:{session_id}:
        this.redisKeyPrefix = "wencairobot:user_dialog:" + userId + ":" + sessionId + ":";
        this.redis = new RedisMock();
        this.tokenizer = new QwenTokenizer();
    }

    /**
     * 简化构造器（向后兼容）
     */
    public RedisBackedChatMemory(String sessionId, int maxTokens) {
        this("default_user", sessionId, maxTokens);
    }

    @Override
    public void add(ChatMessage message) {
        // 1. 从 Redis 加载当前消息列表
        List<ChatMessage> messages = messages();

        // 2. 添加新消息
        messages.add(message);

        // 3. 检查是否超出 token 限制，超出则裁剪
        while (calculateTotalTokens(messages) > maxTokens && messages.size() > 2) {
            // 移除最早的消息（保留最近的对话）
            messages.remove(0);
        }

        // 4. 存回 Redis
        redis.setList(redisKeyPrefix + "messages", messages);

        // 5. 刷新 TTL（30 分钟）- 每次对话后续期
        redis.expire(redisKeyPrefix + "messages", 1800);

        // 6. 异步写入对话日志（生产环境：发送到 Kafka）
        // Kafka 消息体：{userId, sessionId, message, timestamp}
        // 下游消费者：写入 MySQL 做永久存储（合规审计）
        logDialogAsync(message);
    }

    @Override
    public List<ChatMessage> messages() {
        // 从 Redis 加载消息列表
        return redis.getList(redisKeyPrefix + "messages");
    }

    @Override
    public void clear() {
        redis.delete(redisKeyPrefix + "messages");
    }

    /**
     * 异步写入对话日志
     * 生产环境实现：
     * - 发送 Kafka 消息：ProducerRecord("dialog-log", key, value)
     * - Kafka Schema: {userId, sessionId, role, content, timestamp}
     * - 下游消费：Flink/Spark 流处理 → MySQL
     */
    private void logDialogAsync(ChatMessage message) {
        // TODO: 生产环境替换为 Kafka Producer
        // 示例代码:
        // DialogLog log = new DialogLog(userId, sessionId, message, System.currentTimeMillis());
        // kafkaProducer.send(new ProducerRecord<>("dialog-log", userId, log.toJson()));
        System.out.println("[Kafka] 异步写入对话日志：user=" + userId + ", session=" + sessionId);
    }

    /**
     * 计算消息列表的总 token 数
     */
    private int calculateTotalTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += tokenizer.estimateTokenCountInMessage(msg);
        }
        return total;
    }

    // ==================== Redis Mock（生产环境替换为真实 Redis）====================

    /**
     * Redis 模拟实现（生产环境用 Jedis / Lettuce / Redisson）
     *
     * 生产环境配置建议：
     * - Redisson: 支持 Redis 对象映射，API 友好
     * - Lettuce: Spring Data Redis 默认客户端，支持异步
     * - Jedis: 经典客户端，性能好
     *
     * Key 命名规范（按你的生产环境）：
     * - 短期记忆：wencairobot:user_dialog:{user_id}:{session_id}:messages (List, TTL 30 分钟)
     * - 用户画像：wencairobot:user_profile:{user_id}: (Hash, 长期存储)
     * - 对话日志：Kafka → MySQL (永久存储)
     */
    static class RedisMock {
        private static final java.util.Map<String, Object> store = new java.util.concurrent.ConcurrentHashMap<>();

        public <T> void setList(String key, List<T> value) {
            store.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> List<T> getList(String key) {
            Object value = store.get(key);
            return value != null ? (List<T>) value : new ArrayList<>();
        }

        public void delete(String key) {
            store.remove(key);
        }

        public void expire(String key, int seconds) {
            // 生产环境：redis.expire(key, seconds)
            // 这里模拟：实际应该用 Redis KeySpace 通知或定期任务清理
            System.out.println("[Redis] TTL 刷新：" + key + " -> " + seconds + "秒");
        }
    }

    // ==================== Main 测试 ====================

    public static void main(String[] args) {
        // 模拟生产环境：多实例部署 + 服务端生成 session_id
        String userId = "user_888888";  // 服务端生成的用户 ID
        String sessionId = "sess_20260314_123456";  // 服务端生成的会话 ID（防止信息泄漏）

        System.out.println("=== 生产环境模拟：多实例部署 ===");
        System.out.println("用户 ID: " + userId);
        System.out.println("会话 ID: " + sessionId);
        System.out.println("Redis Key: wencairobot:user_dialog:" + userId + ":" + sessionId + ":messages\n");

        // 实例 1：用户第一次提问（北京机房）
        System.out.println("=== 实例 1（北京机房）：用户第一次提问 ===");
        RedisBackedChatMemory memory1 = new RedisBackedChatMemory(userId, sessionId, 3000);
        memory1.add(UserMessage.from("我叫张三，想了解一下稳健型理财"));
        memory1.add(AiMessage.from("好的，张三先生。稳健型理财通常指..."));
        System.out.println("实例 1 记忆条数：" + memory1.messages().size());
        System.out.println("[Kafka] 日志已异步写入，下游消费到 MySQL 用于合规审计\n");

        // 实例 2：用户第二次提问（上海机房，负载均衡）
        System.out.println("=== 实例 2（上海机房）：用户第二次提问 ===");
        RedisBackedChatMemory memory2 = new RedisBackedChatMemory(userId, sessionId, 3000);
        System.out.println("实例 2 能读取之前的记忆吗？" + (memory2.messages().size() > 0 ? "能！✅" : "不能 ❌"));
        System.out.println("实例 2 记忆条数：" + memory2.messages().size());
        memory2.add(UserMessage.from("那北京的理财产品呢？"));
        memory2.add(AiMessage.from("北京地区的稳健型理财..."));
        System.out.println("实例 2 更新后记忆条数：" + memory2.messages().size());
        System.out.println("[Redis] TTL 刷新：30 分钟\n");

        // 实例 3：用户第三次提问（深圳机房，又一台机器）
        System.out.println("=== 实例 3（深圳机房）：用户第三次提问 ===");
        RedisBackedChatMemory memory3 = new RedisBackedChatMemory(userId, sessionId, 3000);
        System.out.println("实例 3 能读取之前的记忆吗？" + (memory3.messages().size() > 0 ? "能！✅" : "不能 ❌"));
        System.out.println("实例 3 记忆条数：" + memory3.messages().size());
        System.out.println("完整对话历史：");
        for (ChatMessage msg : memory3.messages()) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            System.out.println("  [" + role + "]: " + msg.toString().substring(0, Math.min(40, msg.toString().length())) + "...");
        }

        System.out.println("\n=== 结论 ===");
        System.out.println("✅ Redis 持久化后，多实例部署也能共享短期记忆！");
        System.out.println("✅ 服务端生成 session_id，防止客户端伪造会话！");
        System.out.println("✅ Kafka 异步写入日志，满足金融合规审计要求！");
    }
}
