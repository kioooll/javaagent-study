# Memory 系统面试考点 Q&A

> 📝 从 Redis 到 Kafka，生产环境的坑与面试必问题
> 最后更新：2026-03-16

---

## 一、Redis 数据结构选型

### Q1：为什么用 List 存对话历史？不用 String 或 Hash？

**答案要点**：

| 数据结构 | 追加操作 | 裁剪操作 | 并发安全 | 适用场景 |
|---------|---------|---------|---------|---------|
| **List** | LPUSH O(1) | LTRIM O(1) | ✅ 原子 | ✅ 对话历史 |
| String | SET O(N) | - | ❌ 竞态 | ❌ 不适合 |
| Hash | HSET O(1) | 不支持 | ✅ 原子 | ❌ 无需裁剪 |

**List 方案代码**：
```bash
# 追加消息
LPUSH wencairobot:user_dialog:u123:s456:messages "{\"role\":\"user\",\"content\":\"...\"}"

# 裁剪保留最新 100 条
LTRIM wencairobot:user_dialog:u123:s456:messages 0 99

# 读取全部
LRANGE wencairobot:user_dialog:u123:s456:messages 0 -1
```

**String 方案的问题**：
```java
// 每次追加需要：读整个 JSON → 拼接 → 写回
String json = redis.get(key);  // 读 20KB
List<Message> messages = parse(json);  // O(N) 反序列化
messages.add(newMessage);
redis.set(key, toJson(messages));  // 写 20KB

// 并发问题：
// 线程 A: GET → [msg1, msg2]
// 线程 B: GET → [msg1, msg2]
// 线程 A: SET → [msg1, msg2, msgA]
// 线程 B: SET → [msg1, msg2, msgB]  ← msgA 丢了！
```

**关键结论**：
- List 的 `LPUSH + LTRIM` 是原子操作，无并发问题
- List 追加开销 O(1)，String 追加开销 O(N)
- 100 条对话时，String 方案带宽是 List 的 100 倍

---

### Q2：List 随机访问的复杂度是多少？

**答案**：
```
LRANGE key start end 的复杂度是 O(S + N)
- S: 起始索引
- N: 返回元素个数

取第 500 条：LRANGE key 500 500 → O(501)
读取全部 1000 条：LRANGE key 0 -1 → O(1000)
```

**实际影响**：
- 对话场景 99% 的请求只读**最近 10 条** → `LRANGE key 0 10` → O(10)
- 用户问"我第 3 条说了什么" → `LRANGE key 2 2` → O(3)，可接受

---

## 二、TTL 设计

### Q3：为什么 TTL 是 30 分钟？不是 5 分钟或 1 小时？

**答案框架**：

**1. 用户行为数据支撑**
```
分析线上 7 天对话日志（500 万 session）：

对话间隔分布：
- P50：30 秒
- P90：8 分钟
- P95：15 分钟
- P99：25 分钟

结论：30 分钟覆盖 99% 的用户场景
```

**2. 成本核算**
```
| TTL | 覆盖率 | Redis 内存 | 性价比 |
|-----|-------|-----------|--------|
| 10 分钟 | P90 ≈ 80% | 100GB | 覆盖率不够 |
| 30 分钟 | P99 ≈ 99% | 150GB | ✅ 最优 |
| 1 小时 | P99.5 ≈ 99.5% | 300GB | 边际收益递减 |
```

**3. 续期策略**
```java
// 每次对话刷新 TTL（活跃 session 永不过期）
redis.expire(key, 1800);  // 30 分钟

// 优化：限制刷新频率（防风暴）
Long lastRefresh = redis.get(key + ":ttl_refresh");
if (lastRefresh == null || now - lastRefresh > 300) {
    redis.expire(key, 1800);
    redis.set(key + ":ttl_refresh", now, 1800);
}
```

---

### Q4：如何防止 TTL 集中过期导致雪崩？

**答案**：
```java
// TTL 加随机抖动（Jitter）
int baseTTL = 1800;  // 30 分钟
int jitter = new Random().nextInt(300);  // 0-5 分钟
int ttl = baseTTL + jitter;
redis.expire(key, ttl);
```

**效果**：
- 原本 10:00 创建的 10 万 session 会在 10:30 同时过期
- 加了 jitter 后，过期时间分散在 10:30-10:35
- 避免数据库瞬时压力飙升

---

## 三、缓存穿透/击穿/雪崩

### Q5：穿透、击穿、雪崩的区别和解决方案？

**完整对比**：

| 问题 | 关键词 | 原因 | 解决方案 |
|------|--------|------|---------|
| **穿透** | 不存在的数据 | 恶意攻击/脏数据 | 缓存空值、布隆过滤器 |
| **击穿** | 单个热点 key | 并发量大 + 刚好过期 | 互斥锁、永不过期 |
| **雪崩** | 大量 key 同时 | TTL 集中 | 随机抖动、限流降级 |

---

### 穿透解决方案

```java
public List<ChatMessage> getMessages(String sessionId) {
    List<ChatMessage> messages = redis.getList(key);

    if (messages == null) {
        // 缓存空值，TTL 缩短（5 分钟）
        redis.setex(key + ":empty", 300, "EMPTY_MARKER");
        return emptyList();
    }

    if ("EMPTY_MARKER".equals(messages)) {
        return emptyList();  // 空值缓存命中
    }

    return messages;
}
```

**布隆过滤器（大规模场景）**：
```java
// 初始化时把所有存在的 session_id 加入
BloomFilter bloom = BloomFilter.create(...);
bloom.put("session_001");

// 请求时先过滤
if (!bloom.mightContain(sessionId)) {
    return emptyList();  // 一定不存在
}
```

---

### 击穿解决方案

```java
public List<ChatMessage> getMessages(String sessionId) {
    String lockKey = "lock:session:" + sessionId;

    // 尝试获取锁（5 秒过期，防止死锁）
    boolean locked = redis.setnx(lockKey, "1", 5);

    if (locked) {
        try {
            // 双重检查（其他线程可能已重建）
            List<ChatMessage> messages = redis.getList(key);
            if (messages != null) {
                return messages;
            }

            // 重建缓存
            messages = loadFromDB(sessionId);
            redis.setList(key, messages);
            redis.expire(key, 1800);
            return messages;
        } finally {
            redis.del(lockKey);
        }
    } else {
        // 没抢到锁，等待重试
        Thread.sleep(50);
        return getMessages(sessionId);  // 递归重试
    }
}
```

---

## 四、Redis 集群

### Q6：单机 Redis 容量上限是多少？

**答案**：
```
单机 Redis 容量受两个因素限制：

1. 内存大小
   - 云服务器：最大 128GB（阿里云 Redis 企业版）
   - 自建：取决于单机内存（通常 32-64GB）

2. 性能瓶颈
   - 单线程模型：QPS 上限约 10-15 万（简单命令）
   - 大 Key 操作：阻塞主线程（LRANGE 大列表）
   - 网络带宽：1Gbps ≈ 125MB/s

我们场景的估算：
- 单 session：2KB（10 条消息 × 200 字节）
- 100 万并发 session：2GB
- 1000 万并发 session：20GB

结论：
- 100 万 DAU：单机 2GB 够用
- 1000 万 DAU：需要集群（20GB 超单机推荐配置）
```

---

### Q7：Redis Cluster 的 Key 路由规则是什么？

**答案**：
```
Redis Cluster 路由算法：

1. 计算 CRC16
   CRC16("wencairobot:user_dialog:u123:s456:messages")

2. 取模定位 Slot
   Slot = CRC16(key) % 16384

3. Slot 映射到节点
   Slot 0-5460 → Master A
   Slot 5461-10922 → Master B
   Slot 10923-16383 → Master C
```

**Hash Tag 优化（同用户数据集中）**：
```bash
# Key 中包含 {} 时，只 hash {} 内的内容
wencairobot:user_dialog:{user_123}:session1:messages
wencairobot:user_dialog:{user_123}:session2:messages

# 计算：
CRC16("user_123") % 16383 → Slot X

# 结果：
同一个 user_123 的所有 session 都在 Slot X → 同一个 Master 节点
```

**好处**：
1. 同用户数据集中，减少跨节点查询
2. 可以用 `MULTI/EXEC` 事务（同节点才支持）
3. 用户画像 + 短期记忆在同一片，方便关联查询

---

### Q8：Master 节点挂了会发生什么？

**答案**：
```
故障转移流程：

1. 检测故障（Gossip 协议）
   - 其他节点 Ping 不通 Master A
   - 超过 timeout（默认 15 秒）标记为 PFAIL

2. 主从切换
   - Slave A 发起投票
   - 超过半数 Master 同意 → Slave A 升级为 Master

3. 客户端重定向
   - Client 发送命令到新 Master
   - 或收到 MOVED 响应，自动更新路由表

时间：
- 检测故障：15 秒
- 切换：1-2 秒
- 总耗时：约 15-20 秒

降级方案：
- 读操作：切从节点（旧数据，但可用）
- 写操作：排队等待或返回"系统繁忙"
```

---

## 五、Kafka 篇

### Q9：为什么用 Kafka？不直接写 MySQL 吗？

**答案框架**：

**1. 流量数据支撑**
```
日请求量：170 万
流量分布：
- 盘中 4 小时（9:30-13:30）：51 万请求，占 30%
- 盘后 4 小时（15:00-19:00）：34 万请求，占 20%
- 其余 16 小时：85 万请求，占 50%

峰值 QPS 计算：
- 盘中 4 小时平均：51 万 / (4×3600) ≈ 35 QPS
- 但实际峰值（开盘/收盘集中）：峰值约 150-200 QPS
- 突发热点（某股票暴涨）：瞬时 1000+ QPS

峰谷差距：10-15 倍
```

**2. 直写 MySQL 的问题**
```
场景 1：突发热点
- 1000 人同时问"XX 股票怎么样"
- 瞬时 QPS 飙升到 1000+
- MySQL 单机写 QPS 约 500-1000（考虑索引、事务）
- 直接写库：可能超时/锁等待

场景 2：下游多消费者
- 消费者 1：写 MySQL（合规审计）
- 消费者 2：实时风控（检测敏感词）
- 消费者 3：数据分析（用户行为画像）
- 如果直写 MySQL，下游要轮询数据库
```

**3. Kafka 方案的优势**
```
| 考虑 | 直写 MySQL | Kafka + MySQL |
|------|-----------|--------------|
| 写压力 | 同步写，QPS 高时 DB 崩 | Kafka 削峰，DB 按能力消费 |
| 解耦 | 日志写入和主链路耦合 | 主链路只写 Redis，日志异步 |
| 扩展 | DB 写满只能分库分表 | 加消费者实例即可 |
| 容错 | DB 挂了主链路失败 | Kafka 有缓冲，DB 挂可重试 |
| 多消费者 | 要改代码支持多订阅方 | 天然支持多 Consumer Group |
```

---

### Q10：Kafka Partition 怎么设计？

**答案**：

**1. Partition Key 选择**
```
| Key 方案 | 路由结果 | 顺序性 | 问题 |
|---------|---------|-------|------|
| 无 Key（随机） | 均匀分布 | 无序 | 同用户消息可能乱序 |
| sessionId | 按会话分散 | 会话内有序 | 同用户多会话无序 |
| userId（推荐） | 同用户同 Partition | 用户级有序 | 可能热点倾斜 |

我们的选择：userId

原因：
1. 同用户的多轮对话需要有序（用户问"我刚才说的"）
2. 下游按用户聚合（用户画像更新）
3. sessionId 太多，分散过度

代码：
```java
ProducerRecord<String, DialogLog> record =
    new ProducerRecord<>("dialog-log", userId, log);
// Kafka 内部：hash(userId) % partitionCount → Partition X
```

**2. Partition 数计算**
```
计算公式：Partition 数 = 峰值 QPS / 单 Partition 吞吐

我们场景：
- 峰值 QPS：1000 msg/s
- 单 Partition 吞吐：~500 msg/s（保守估计）
- 计算：1000 / 500 = 2

但我们会设 12 个 Partition，原因：
1. 预留冗余（未来流量增长）
2. 消费者并行度（12 个消费者同时消费）
3. 避免热点（大 V 用户不会占满单 Partition）

容量校验：
- 12 Partition × 500 msg/s = 6000 msg/s 吞吐上限
- 峰值 1000 msg/s → 利用率 17%，充足
```

**3. 热点问题处理**
```
问题：大 V 用户（如 10 万粉丝）的对话量占总量 50%

解决方案：大 V 单独 Topic
```java
if (isVip(userId)) {
    producer.send(new ProducerRecord<>("dialog-log-vip", userId, log));
} else {
    producer.send(new ProducerRecord<>("dialog-log", userId, log));
}
```

---

### Q11：如何保证消息不丢失？

**答案**：

**三个环节配置**：

**1. Producer 端**
```java
props.put("acks", "all");           // 所有副本确认
props.put("retries", 3);            // 失败重试
props.put("enable.idempotence", "true");  // 幂等写入
```

**2. Broker 端**
```properties
min.insync.replicas=2    # 至少 2 个副本同步写入
unclean.leader.election.enable=false  # 禁止不同步的副本当选 leader
```

**3. Consumer 端**
```java
props.put("enable.auto.commit", "false");  // 关闭自动提交

// 业务处理完再提交 offset
try {
    processMessage(record);  // 写 MySQL
    consumer.commitSync();   // 提交
} catch (Exception e) {
    log.error("处理失败", e);
    // 不提交，下次重试
}
```

---

### Q12：如何保证消息不重复？

**答案**：
```
标准答案：不能完全保证 Exactly-Once，只能做到端到端幂等。

重复场景：
1. Producer 重试 → Broker 收到两次相同消息
2. Consumer 提交前崩溃 → 重启后重复消费

解决方案：MySQL 唯一键防重
```sql
CREATE TABLE dialog_log (
    message_id VARCHAR(64) PRIMARY KEY,  -- 全局唯一 ID
    user_id VARCHAR(32),
    session_id VARCHAR(32),
    role VARCHAR(16),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_session (user_id, session_id)
);

-- 插入时用 INSERT IGNORE
INSERT IGNORE INTO dialog_log (message_id, ...) VALUES (?, ...);
```

```java
public void processMessage(ConsumerRecord<String, DialogLog> record) {
    DialogLog log = parse(record.value());

    // 幂等写入（已存在则跳过）
    dao.insertIgnore(log);

    // 提交 offset
    consumer.commitSync();
}
```

---

### Q13：消费积压怎么处理？

**答案框架**：

**1. 监控发现**
```
监控指标：

1. Consumer Lag（核心指标）
   - 定义：已生产消息数 - 已消费消息数
   - 命令：kafka-consumer-groups --describe --group xxx
   - 告警阈值：Lag > 10 万 → P2 告警

2. 消费延迟（Time Lag）
   - 定义：最新消息时间戳 - 最后消费消息时间戳
   - 告警阈值：延迟 > 5 分钟 → P1 告警

监控工具：
- Prometheus + Kafka Exporter
- Grafana 仪表盘
- 钉钉/企微告警
```

**2. 应急处理**
```bash
# 第 1 步：确认消费者状态
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --describe --group dialog-log-group

# 第 2 步：同 Group 扩容（推荐）
# 启动 4 个新消费者（相同 Group ID）
for i in {1..4}; do
  java -jar consumer.jar \
    --group dialog-log-group \
    --batch-size 1000 &
done

# Rebalance 后（约 30 秒）
# Consumer-1 → Partition 0-2
# Consumer-2 → Partition 3-5
# ...
# 消费速度：200 msg/s × 6 = 1200 msg/s
```

**3. 批量处理优化**
```java
// 配置
props.put("fetch.min.bytes", "1048576");  // 最少拉取 1MB
props.put("fetch.max.wait.ms", "500");    // 最多等 500ms
props.put("max.poll.records", "1000");    // 每次最多拉取 1000 条

// 消费逻辑
ConsumerRecords<String, DialogLog> records = consumer.poll(Duration.ofMillis(1000));

List<DialogLog> batch = new ArrayList<>();
for (ConsumerRecord<String, DialogLog> record : records) {
    batch.add(record.value());

    if (batch.size() >= 1000) {
        dao.batchInsert(batch);  // 批量插入性能提升 10 倍
        consumer.commitSync();
        batch.clear();
    }
}
```

**4. 根因分析**
```
根因分析方向：

1. 消费者太慢？
   - 检查 SQL 执行时间（慢查询？）
   - 检查批量大小（batch.size 配置）

2. 数据库太慢？
   - MySQL 连接池满？
   - 锁等待（死锁？）

3. 网络问题？
   - Kafka Broker 网络延迟？

4. 突发流量？
   - 是否有活动导致流量激增？

实际案例：
有一次积压是因为 MySQL 慢查询：
- 现象：Lag 从 1 万飙升到 50 万
- 排查：消费者日志显示 insert 耗时从 10ms 升到 500ms
- 根因：dialog_log 表没有 idx_user_session 索引
- 解决：加索引后消费速度恢复
```

---

## 六、架构设计综合题

### Q14：你的 Memory 系统架构是怎样的？

**完整答案**：
```
我们的 Memory 系统分为三层架构：

┌─────────────────────────────────────────────────────────┐
│                    用户请求                              │
└─────────────────────────┬───────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          │               │               │
          ▼               ▼               ▼
    ┌───────────┐  ┌───────────┐  ┌──────────────┐
    │   Redis   │  │   Kafka   │  │    MySQL     │
    │ 短期记忆   │  │  对话日志  │  │  永久存储     │
    │ TTL 30min  │  │  异步削峰  │  │  合规审计     │
    └───────────┘  └───────────┘  └──────────────┘

1. 热数据层（Redis）
   - 用途：存储短期对话记忆（最近 10 轮或 3000 token）
   - Key 设计：wencairobot:user_dialog:{user_id}:{session_id}:messages
   - 数据结构：List（支持 LPUSH + LTRIM 原子裁剪）
   - TTL 策略：30 分钟，每次对话续期
   - 容量估算：10 万并发 session × 2KB = 200MB

2. 缓冲层（Kafka）
   - 用途：对话日志异步写入，削峰填谷
   - Topic：dialog-log，12 Partition
   - 可靠性：acks=all，min.insync.replicas=2
   - 幂等：消费者手动提交 offset + MySQL 唯一键防重
   - 吞吐量：峰值 1000 QPS → 匀速 200 QPS 消费

3. 冷数据层（MySQL）
   - 用途：永久存储，合规审计
   - 表设计：按 user_id 分片，支持按 session_id 查询
   - 索引：idx_user_session (user_id, session_id)
   - retention：永久存储（金融合规要求）

关键设计决策：
1. 为什么 Redis 不用持久化？
   - 短期记忆丢了就丢了，用户重聊即可
   - 成本优先，性能优先

2. 为什么 Kafka 不直写 MySQL？
   - 削峰：峰值 1000 QPS，MySQL 单机扛不住
   - 解耦：下游多消费者（审计、风控、画像）
   - 容错：DB 挂了可重试，不阻塞主链路

3. 为什么 session_id 服务端生成？
   - 防止客户端伪造会话 ID
   - 防止遍历窃取他人对话历史
```

---

### Q15：如果让你重新设计，你会做什么改进？

**答案**：
```
如果重新设计，我会做 3 个改进：

1. 加本地缓存降级层（Caffeine）
   问题：Redis 挂了，主链路直接失败
   改进：Redis 失败 → 降级到 Caffeine 本地缓存
   代价：多实例记忆不共享，但比直接失败好

2. 加监控告警
   问题：故障靠用户投诉发现
   改进：
   - Redis 内存使用率 > 80% 告警
   - Kafka Lag > 10 万 告警
   - 写入成功率 < 99.9% 告警

3. 加长期记忆（Embedding 存储）
   问题：TTL 30 分钟后，历史对话永久丢失
   改进：
   - 重要对话向量化存储（FAISS/PGVector）
   - 用户问"我上次说的 XX"时，语义检索
   代价：增加 Embedding 成本，但体验提升

优先级：
P0：监控告警（先能发现问题）
P1：降级方案（保证可用性）
P2：长期记忆（提升体验）
```

---

## 七、面试自查清单

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

*本文档基于生产环境实战经验整理，涵盖 Memory 系统设计中的 Redis 和 Kafka 核心考点。*
