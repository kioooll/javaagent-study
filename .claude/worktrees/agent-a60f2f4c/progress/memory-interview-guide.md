# Memory 系统面试考点全解

> 📝 从 Redis 到 Kafka，生产环境的坑与面试必问题

---

## 一、Redis 篇

### 1.1 数据结构选型

**面试问题**：为什么用 List 而不是 String 或 Hash？

| 数据结构 | 适用场景 | 为什么不适合 |
|---------|---------|-------------|
| **List** | ✅ 有序消息队列，支持 LPUSH + LRANGE | - |
| String | 单值存储 | 无法追加消息，每次要反序列化整个字符串 |
| Hash | 键值对存储 | 不支持有序列表，无法按范围查询 |
| Set | 无序去重 | 消息需要保序，不能去重 |
| ZSet | 带权重的有序集合 | 过度设计，增加内存开销 |

**生产实践**：
```bash
# List 操作命令
LPUSH wencairobot:user_dialog:u123:s456:messages "{\"role\":\"user\",\"content\":\"...\"}"
LRANGE wencairobot:user_dialog:u123:s456:messages 0 -1  # 读取全部
LTRIM wencairobot:user_dialog:u123:s456:messages 0 99   # 裁剪保留最新 100 条
```

---

### 1.2 Key 命名规范

**面试问题**：Key 设计要考虑什么？

**答案要点**：
1. **命名空间隔离**：`wencairobot:` 前缀，避免与其他业务冲突
2. **业务语义**：`user_dialog` 一眼看出用途
3. **分片友好**：`{user_id}:{session_id}` 可作为 Redis Cluster 的分片键
4. **可读性**：运维看到 Key 能知道含义

**反例**：
```bash
# 烂设计
session_123  # 哪个用户的？什么用途？
user:123     # 太泛，和其他业务冲突

# 好设计
wencairobot:user_dialog:{user_id}:{session_id}:messages
wencairobot:user_profile:{user_id}:
```

---

### 1.3 TTL 策略

**面试问题**：为什么 TTL 是 30 分钟？为什么每次对话续期？

**答案要点**：

| 考虑因素 | 分析 |
|---------|------|
| **用户行为** | 金融投顾场景，单 session 平均对话 5-10 轮，耗时 5-15 分钟 |
| **成本** | TTL 太短 → 用户思考间隙过期；TTL 太长 → 闲置 session 占内存 |
| **续期策略** | 每次对话续期 → 活跃 session 永不过期，闲置 session 自动清理 |

**30 分钟的由来**：
- P90 用户对话间隔 < 10 分钟
- P99 用户对话间隔 < 25 分钟
- 30 分钟覆盖 99% 场景，兼顾成本

**面试扩展**：
- 如果用户问"我刚才说的"，TTL 内但消息被裁剪了怎么办？ → 引入长期记忆（Embedding 存储）
- 如果 Redis 挂了，TTL 还有意义吗？ → RDB/AOF 持久化会保留 TTL

---

### 1.4 持久化策略

**面试问题**：Redis 持久化用 RDB 还是 AOF？

| 策略 | 机制 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|---------|
| **RDB** | 定时快照 | 恢复快，文件小 | 丢数据（两次快照之间） | 缓存场景 |
| **AOF** | 记录每条写命令 | 数据完整 | 恢复慢，文件大 | 持久化场景 |
| **Mixed** | RDB + AOF | 折中方案 | 配置复杂 | 推荐 |

**Memory 系统选择**：
- **短期记忆**：RDB 即可（丢了就丢了，用户重聊）
- **用户画像**：AOF（不能丢）
- **对话日志**：Kafka → MySQL（Redis 只是缓冲）

**生产配置**：
```conf
# Redis.conf
save 900 1        # 15 分钟有 1 个 key 变化就快照
save 300 10       # 5 分钟有 10 个 key 变化
save 60 10000     # 1 分钟有 1 万个 key 变化

appendonly yes    # 开启 AOF
appendfsync everysec  # 每秒刷盘（性能和数据安全折中）
```

---

### 1.5 内存淘汰策略

**面试问题**：Redis 内存满了怎么办？

**答案要点**：

Redis 8 种淘汰策略：
```
noeviction          # 不淘汰，写操作报错（默认）
allkeys-lru         # 所有 key 按 LRU 淘汰
volatile-lru        # 有 TTL 的 key 按 LRU 淘汰
allkeys-lfu         # 所有 key 按访问频率淘汰
volatile-lfu        # 有 TTL 的 key 按访问频率淘汰
allkeys-random      # 随机淘汰
volatile-random     # 随机淘汰有 TTL 的
volatile-ttl        # 按剩余 TTL 淘汰
```

**Memory 系统推荐**：`allkeys-lru` 或 `volatile-lru`

**原因**：
- 对话记忆有冷热之分（最近对话是热数据）
- LRU 自动保留活跃 session
- 配合 TTL，双重保障

---

### 1.6 集群方案

**面试问题**：单机 Redis 容量不够怎么办？

| 方案 | 优点 | 缺点 | 适用阶段 |
|------|------|------|---------|
| **主从复制** | 高可用，读扩展 | 写不扩展，故障切换手动 | 日活 < 10 万 |
| **哨兵模式** | 自动故障切换 | 写不扩展 | 日活 10-50 万 |
| **Redis Cluster** | 水平扩展，自动分片 | 运维复杂 | 日活 > 50 万 |
| **Codis/Twemproxy** | 透明代理，运维友好 | 单点故障风险 | 中等规模 |

**Redis Cluster 原理**：
```
客户端 → Proxy/Smart Client → Slot(0-16383) → Master/Slave

Key 路由：CRC16(key) % 16383 → Slot → Node
```

**Memory 系统分片键**：`{user_id}`
```bash
# 同一个用户的 session 在同一片（Hash Tag）
wencairobot:user_dialog:{user_id}:session1:messages
wencairobot:user_dialog:{user_id}:session2:messages
```

---

### 1.7 缓存穿透/击穿/雪崩

**面试必问**：三个问题的区别和解决方案

| 问题 | 现象 | 原因 | 解决方案 |
|------|------|------|---------|
| **穿透** | 查不存在的数据 | 恶意攻击/脏数据 | 布隆过滤器、缓存空值 |
| **击穿** | 热点 key 过期瞬间 | 并发量大 | 互斥锁、永不过期 |
| **雪崩** | 大量 key 同时过期 | TTL 设置集中 | 随机 TTL、限流降级 |

**Memory 系统应对**：

1. **穿透**（查不到的 session）：
   ```java
   // 缓存空 session
   if (messages == null || messages.isEmpty()) {
       redis.setex(key, 60, EMPTY_MARKER);  // 空值缓存 1 分钟
   }
   ```

2. **击穿**（热门用户）：
   ```java
   // 互斥锁
   String lockKey = "lock:" + sessionId;
   if (redis.setnx(lockKey, "1", 5)) {
       try {
           // 重建缓存
           List<ChatMessage> messages = loadFromDB(sessionId);
           redis.setList(key, messages);
       } finally {
           redis.del(lockKey);
       }
   } else {
       // 等待重试
       Thread.sleep(50);
       return getMessages(sessionId);
   }
   ```

3. **雪崩**（批量过期）：
   ```java
   // TTL 加随机值
   int baseTTL = 1800;  // 30 分钟
   int randomJitter = new Random().nextInt(300);  // 0-5 分钟
   redis.expire(key, baseTTL + randomJitter);
   ```

---

### 1.8 性能优化

**面试问题**：如何提升 Redis 读写性能？

| 优化点 | 方案 | 效果 |
|--------|------|------|
| **批量操作** | Pipeline 批量读写 | 减少 RTT 次数 |
| **大 Key 拆分** | List 按 100 条分片 | 避免阻塞主线程 |
| **压缩存储** | MessagePack 替代 JSON | 节省 30-50% 内存 |
| **本地缓存** | Caffeine + Redis 两级 | 热点数据微秒级 |

**Pipeline 示例**：
```java
// 烂代码（N 次 RTT）
for (ChatMessage msg : messages) {
    redis.lpush(key, msg.toJson());
}

// 好代码（1 次 RTT）
Pipeline p = redis.pipelined();
for (ChatMessage msg : messages) {
    p.lpush(key, msg.toJson());
}
p.sync();
```

---

## 二、Kafka 篇

### 2.1 为什么用 Kafka

**面试问题**：为什么对话日志不直接写 MySQL，要绕一圈 Kafka？

**答案要点**：

| 考虑 | 直接写 MySQL | Kafka + MySQL |
|------|------------|--------------|
| **写压力** | 每次对话同步写库，QPS 高时 DB 扛不住 | Kafka 削峰，DB 按能力消费 |
| **解耦** | 日志写入和主链路耦合 | 主链路只写 Redis，日志异步 |
| **扩展性** | DB 写满只能分库分表 | 加消费者实例即可 |
| **容错** | DB 挂了主链路失败 | Kafka 有缓冲，DB 挂了可重试 |
| **多消费者** | 要改代码支持多订阅方 | 天然支持多 Consumer Group |

**生产场景**：
- 日活 100 万 → 日均对话 5000 万条 → 平均 QPS 600，峰值 3000+
- MySQL 单机写 QPS < 1000 → 必须削峰

---

### 2.2 Topic 设计

**面试问题**：Kafka Topic 怎么设计？

**关键参数**：
```
Topic: dialog-log
Partition: 12  # 根据吞吐量设定
Replication: 3  # 高可用
Retention: 7d   # 保留 7 天
```

**Partition 数计算**：
```
目标吞吐量：峰值 3000 msg/s
单 Partition 吞吐：~500 msg/s
Partition 数 = 3000 / 500 = 6（向上取整到 12，预留冗余）
```

**Partition Key 选择**：
```java
// 方案 1：按 user_id 分区（同用户消息有序）
ProducerRecord<String, DialogLog> record =
    new ProducerRecord<>("dialog-log", user_id, log);

// 方案 2：随机分区（负载均衡）
ProducerRecord<String, DialogLog> record =
    new ProducerRecord<>("dialog-log", log);
```

**推荐**：按 `user_id` 分区 → 同用户消息在同一个 Partition，保证顺序，方便下游按用户聚合。

---

### 2.3 消息可靠性

**面试必问**：如何保证消息不丢失？

**三个环节**：

| 环节 | 配置 | 说明 |
|------|------|------|
| **Producer** | `acks=all` | 所有副本确认才返回 |
| **Broker** | `min.insync.replicas=2` | 至少 2 个副本同步 |
| **Consumer** | `enable.auto.commit=false` | 手动提交 offset |

**Producer 配置**：
```java
Properties props = new Properties();
props.put("bootstrap.servers", "kafka1:9092,kafka2:9092");
props.put("acks", "all");                    // 所有副本确认
props.put("retries", 3);                     // 失败重试
props.put("retry.backoff.ms", 100);
props.put("enable.idempotence", "true");     // 幂等写入
```

**Consumer 配置**：
```java
props.put("enable.auto.commit", "false");    // 关闭自动提交

// 手动提交（处理完消息后）
try {
    processMessage(record);
    consumer.commitSync();  // 处理完再提交
} catch (Exception e) {
    // 记录失败，不提交 offset，下次重试
    log.error("处理失败", e);
}
```

---

### 2.4 消息重复消费

**面试问题**：如何保证消息不重复？（Exactly-Once 语义）

**答案**：**无法完全避免**，只能做到**端到端幂等**。

**重复场景**：
1. Producer 重试 → Broker 已写入但 ACK 丢失
2. Consumer 提交前崩溃 → 重启后重复消费

**解决方案**：
```java
// 消费者幂等处理
public void processMessage(DialogLog log) {
    // 1. 检查是否已处理（MySQL 唯一键）
    if (dao.exists(log.getMessageId())) {
        return;  // 已处理，跳过
    }

    // 2. 写入 MySQL（唯一索引防重）
    dao.insert(log);  // INSERT IGNORE ON DUPLICATE KEY
}
```

**MySQL 表设计**：
```sql
CREATE TABLE dialog_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(64) UNIQUE,  -- 唯一键防重
    user_id VARCHAR(32),
    session_id VARCHAR(32),
    role VARCHAR(16),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_session (user_id, session_id),
    INDEX idx_created (created_at)
);
```

---

### 2.5 消费积压

**面试问题**：消费者挂了，消息积压几百万怎么办？

**应急方案**：

1. **临时扩容消费者**：
   ```bash
   # 原消费者组：3 个实例
   # 新启动 10 个临时消费者实例（不同 Group ID）
   # 将积压消息转发到新 Topic
   # 原消费者恢复正常后，从新 Topic 回灌
   ```

2. **跳过非关键消息**（金融场景不适用）：
   ```bash
   # 重置 offset 到最新
   kafka-consumer-groups --reset-offsets --to-latest
   ```

3. **批量处理**：
   ```java
   // 消费者批量拉取，批量写入
   ConsumerRecords<String, DialogLog> records = consumer.poll(Duration.ofSeconds(1));
   List<DialogLog> batch = new ArrayList<>();
   for (ConsumerRecord<String, DialogLog> record : records) {
       batch.add(record.value());
       if (batch.size() >= 1000) {
           dao.batchInsert(batch);
           batch.clear();
       }
   }
   ```

---

### 2.6 顺序保证

**面试问题**：如何保证消息顺序？

**答案要点**：
- Kafka 只保证**Partition 内有序**，不保证全局有序
- 同用户消息 → 按 `user_id` 分区 → 同一个 Partition → 有序

**代码**：
```java
// Producer 按 user_id 做 Partition Key
ProducerRecord<String, DialogLog> record =
    new ProducerRecord<>("dialog-log", user_id, log);

// 消费者单线程处理一个 Partition
// 或使用内存队列保证单用户串行处理
```

---

## 三、架构演进篇

### 3.1 容量规划

**面试问题**：如何估算 Redis 和 Kafka 的容量？

**假设**：日活 100 万 DAU，单用户日均 50 轮对话

**Redis 容量**：
```
单 session 平均消息数：10 条
单消息平均大小：200 字节（JSON）
单 session 大小：10 × 200 = 2KB

并发 session 数：DAU × 10% = 10 万（假设 10% 用户同时在线）
Redis 内存：10 万 × 2KB = 200MB

考虑冗余（3 倍）+ 其他数据：200MB × 3 = 600MB
推荐配置：Redis 2GB 主从
```

**Kafka 容量**：
```
日消息量：100 万 × 50 = 5000 万条/天
单消息大小：300 字节（含 metadata）
日数据量：5000 万 × 300 = 15GB/天

保留 7 天：15GB × 7 = 105GB
推荐配置：3 节点，每节点 50GB
```

---

### 3.2 降级方案

**面试问题**：Redis/Kafka 挂了怎么办？

| 故障 | 降级方案 |
|------|---------|
| **Redis 挂** | 1. 切备用 Redis<br>2. 降级到本地内存（Caffeine）<br>3. 只读模式（返回默认回复） |
| **Kafka 挂** | 1. 切备用 Kafka<br>2. 本地文件缓冲（滚动写入）<br>3. 延迟写入（内存队列，Kafka 恢复后补发） |
| **MySQL 挂** | 1. 切备库<br>2. 写入阻塞（用户无感知，日志延迟） |

**降级代码示例**：
```java
public class FallbackChatMemory implements ChatMemory {
    private final ChatMemory primary;  // Redis
    private final ChatMemory fallback; // 本地内存

    @Override
    public void add(ChatMessage message) {
        try {
            primary.add(message);
        } catch (Exception e) {
            log.warn("Redis 失败，降级到本地", e);
            fallback.add(message);
        }
    }
}
```

---

### 3.3 监控指标

**面试问题**：如何监控 Memory 系统健康度？

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| **Redis 内存使用率** | 剩余容量 | > 80% 告警 |
| **Redis QPS** | 负载情况 | 突增 50% 告警 |
| **Redis 响应延迟** | 性能 | P99 > 10ms 告警 |
| **Kafka 消费 Lag** | 积压情况 | > 10 万 告警 |
| **对话日志写入成功率** | 可靠性 | < 99.9% 告警 |
| **Session 命中率** | 记忆共享 | < 95% 异常 |

**Prometheus 指标**：
```promql
# Redis 内存使用率
redis_memory_used_bytes / redis_memory_max_bytes * 100

# Kafka 消费积压
kafka_consumer_lag{topic="dialog-log"}

# 对话日志写入延迟
histogram_quantile(0.99, dialog_log_write_duration_seconds_bucket)
```

---

## 四、高频面试题汇总

### Redis 相关

1. Redis 为什么快？（单线程 + 内存 + 多路复用）
2. Redis 和 Memcached 的区别？（数据结构、持久化、分布式）
3. Redis 集群原理？（Slot、Gossip 协议、故障转移）
4. Redis 分布式锁怎么实现？（SETNX + Lua + 看门狗）
5. Redis 缓存和数据库一致性怎么保证？（延时双删、Canal 订阅）

### Kafka 相关

1. Kafka 为什么吞吐量高？（顺序写、零拷贝、批量发送）
2. Kafka 如何保证消息不丢失？（ACK、副本、手动提交）
3. Kafka 如何保证消息顺序？（Partition Key）
4. Kafka 消费者如何负载均衡？（Consumer Group + Rebalance）
5. Kafka 消息积压怎么处理？（临时扩容、批量处理）

### 架构设计

1. 为什么用 Redis 不用 MySQL 存会话？（访问模式、延迟、并发）
2. 为什么用 Kafka 不用直写？（削峰、解耦、扩展）
3. 如何设计一个支持千万 DAU 的记忆系统？（分片、降级、监控）
4. 如何保证用户数据不泄露？（加密、脱敏、审计）
5. 如何做灰度发布和回滚？（多版本、流量切换）

---

## 五、实战 Checklist

面试前自查：

- [ ] 能清晰解释 Redis Key 设计原则
- [ ] 能说出 TTL 策略背后的用户行为分析
- [ ] 能解释穿透/击穿/雪崩的区别和解决
- [ ] 能手写 Redis LRU 淘汰的伪代码
- [ ] 能说清 Kafka 三副本 + ACK 机制
- [ ] 能解释消费者如何手动提交 offset
- [ ] 能设计幂等消费者（唯一键防重）
- [ ] 能估算容量（内存、磁盘、QPS）
- [ ] 能说出至少 3 种降级方案
- [ ] 能列举 5 个以上监控指标

---

*最后更新：2026-03-14*
