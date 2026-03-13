# Memory 系统设计与实战

> 📝 从短期记忆到长期记忆的完整实现方案

---

## 一、Memory 系统概览

### 1.1 为什么需要 Memory？

```
用户：我叫张三
助手：你好张三！
用户：我来自杭州
助手：好的，杭州是个好地方！
用户：我刚才说我叫什么？

→ 没有 Memory：「你刚才没说过」❌
→ 有 Memory：「你叫张三」✅
```

---

### 1.2 Memory 层级

```
┌───────────────────────────────────────┐
│          长期记忆（Long-term）         │
│  - 用户画像（持久化）                  │
│  - Embedding 语义检索                  │
│  - 对话摘要                            │
└───────────────────────────────────────┘
                    ▲
                    │
┌───────────────────────────────────────┐
│          短期记忆（Short-term）        │
│  - MessageWindow（按条数）            │
│  - TokenWindow（按 token 数）          │
└───────────────────────────────────────┘
```

| 层级 | 容量 | 持久化 | 访问方式 |
|------|------|--------|----------|
| 短期记忆 | 10-20 条 | ❌ 内存 | 顺序读取 |
| 长期记忆 | 无限 | ✅ 文件/DB | 语义检索 |

**⚠️ 生产环境注意事项：**

上表的「短期记忆 - 内存」仅适用于**单机部署/测试环境**。

**生产环境（多实例部署）必须用 Redis 持久化短期记忆！**

原因：
- 负载均衡 → 用户请求随机分发到不同实例
- 单机内存 → 重启清空/实例扩容后丢失
- 会话粘滞（Sticky Session）→ 不推荐，影响负载均衡效果

**正确方案：**
```
短期记忆 → Redis（按 session_id，TTL 30 分钟）
用户画像 → Redis（按 user_id，长期存储）
对话日志 → MySQL（永久存储，合规审计）
```

详见：**二、短期记忆实现 - Redis 持久化方案**

---

## 二、短期记忆实现

### 2.0 生产环境：Redis 持久化方案 ⭐

**问题场景（单机内存的坑）：**
```
用户 A 第一次请求 → 实例 1 → 记忆存在实例 1 内存
用户 A 第二次请求 → 实例 2（负载均衡）→ 实例 2 内存是空的！
→ 用户体验：「这机器人怎么不记得我刚才说什么？」
```

**解决方案：Redis 集中存储**
```
┌─────────────────────────────────────────────────┐
│              用户提问                            │
└─────────────────┬───────────────────────────────┘
                  │
      ┌───────────┼───────────┐
      │           │           │
      ▼           ▼           ▼
┌───────────┐ ┌───────────┐ ┌───────────┐
│  Redis    │ │  Redis    │ │  MySQL    │
│(短期记忆)  │ │(用户画像)  │ │(对话日志)  │
│session_id  │ │user_id    │ │audit      │
└─────┬─────┘ └─────┬─────┘ └─────┬─────┘
      │             │             │
      └──────────┬──┴─────────────┘
                 │
                 ▼
       ┌─────────────────┐
       │   拼成 Prompt    │
       │   给 LLM        │
       └─────────────────┘
```

**Redis 数据结构设计：**
```
# 短期记忆（按 session 维度）
Key: session:{session_id}:messages
Type: List
Value: [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}]
TTL: 30 分钟（每次访问自动续期）

# 用户画像（按用户维度）
Key: user:{user_id}:profile
Type: Hash
Value: {risk_level:"稳健型", investment_horizon:"中线", holdings:"600519,000858"}
TTL: 无（长期存储）

# 对话日志（异步写入 MySQL，Redis 做缓冲）
Key: dialog:queue:{user_id}
Type: List
Value: [{"question":"...","answer":"...","timestamp":"..."}]
TTL: 无（消费后删除）
```

**代码实现（见 day9/RedisBackedChatMemory.java）：**
```java
public class RedisBackedChatMemory implements ChatMemory {
    private final String sessionId;
    private final RedisClient redis;  // Jedis / Lettuce / Redisson

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = redis.getList(key);
        messages.add(message);
        // 超出 token 限制则裁剪最早的消息
        while (calculateTokens(messages) > maxTokens) {
            messages.remove(0);
        }
        redis.setList(key, messages);
        redis.expire(key, 1800);  // 刷新 TTL
    }
}
```

**关键优势：**
- ✅ 多实例共享：用户请求打到任何实例都能读到记忆
- ✅ 自动过期：TTL 30 分钟，无操作自动清理（节省成本）
- ✅ 水平扩展：随时加实例，不用关心会话粘滞

---

### 2.1 MessageWindowChatMemory（按条数）

**机制：** 保留最近 N 条消息，超出后丢弃最早的

```java
ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

// 使用
chatMemory.add(UserMessage.from("你好"));
chatMemory.add(AiMessage.from("你好！"));

List<ChatMessage> history = chatMemory.messages();
```

**工作流程：**
```
初始（10 条限制）：
[M1, M2, M3, M4, M5, M6, M7, M8, M9, M10]

新消息 M11 进来：
[M2, M3, M4, M5, M6, M7, M8, M9, M10, M11]
 ↑
 M1 被丢弃
```

**优点：**
- 简单，性能好
- 适合大多数对话场景

**缺点：**
- 重要信息可能被丢弃（如用户姓名）
- 无法控制 token 成本

---

### 2.2 TokenWindowChatMemory（按 token 数）

**机制：** 保留的 token 总数不超过 N，超出后丢弃最早的消息

```java
// 使用近似 Token 计数器
ChatMemory chatMemory = TokenWindowChatMemory.withMaxTokens(1000);

// 使用精确 Tokenizer（推荐）
QwenTokenizer tokenizer = new QwenTokenizer();
ChatMemory chatMemory = TokenWindowChatMemory.builder()
    .maxTokens(1000)
    .tokenizer(tokenizer)
    .build();
```

**工作流程：**
```
初始（1000 token 限制）：
[M1:50t, M2:100t, M3:200t, M4:300t, M5:350t]
总计：1000 token ✅

新消息 M6(200t) 进来：
[M3:200t, M4:300t, M5:350t, M6:200t]
 ↑
 总计：1050 token → 继续丢弃 M3
 → [M4:300t, M5:350t, M6:200t] = 850 token ✅
```

**优点：**
- 精确控制成本（LLM 按 token 收费）
- 防止单条超长消息占满窗口

**缺点：**
- 仍然会丢失重要信息
- Token 计算有性能开销

---

### 2.3 选择建议

| 场景 | 推荐方案 |
|------|----------|
| 通用对话 | MessageWindow(10-20) |
| 代码/长文本处理 | TokenWindow(2000-4000) |
| 成本控制严格 | TokenWindow + 小 token 数 |
| 需要记住关键信息 | 摘要策略 / Embedding 检索 |

---

## 三、对话摘要策略

### 3.1 核心思想

**不是丢弃旧消息，而是压缩成摘要！**

```
原始对话（10 轮，5000 token）：
"我叫张三" + "在杭州工作" + [8 轮闲聊]

摘要后（1 条，50 token）：
"用户叫张三，在杭州工作。之前询问过天气、报销政策。"
```

---

### 3.2 实现架构

```
┌─────────────────────────────────┐
│     消息队列（LinkedList）       │
│  [SYSTEM: 摘要]                  │
│  [USER: 最近消息 1]              │
│  [AI: 最近回复 1]                │
│  ...                            │
└─────────────────────────────────┘
         │
         │ 超过阈值时
         ▼
┌─────────────────────────────────┐
│     LLM Summarizer              │
│  "总结对话关键信息..."            │
└─────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  更新 SYSTEM 消息（新摘要）       │
│  移除已摘要的旧消息               │
└─────────────────────────────────┘
```

---

### 3.3 摘要 Prompt 设计

```java
String summaryPrompt = """
请总结以下对话的关键信息，保留：
- 用户的个人信息（姓名、地点、职业等）
- 用户询问过的主要问题
- 重要的上下文信息

用简洁的中文，100 字以内。

对话内容：
用户：我叫张三，在杭州工作
助手：你好张三！
...

摘要：
""";
```

**关键：**
- 明确告诉 LLM 要保留什么（个人信息、主要问题）
- 限制长度（100 字以内）
- 指定格式（简洁中文）

---

### 3.4 代价分析

| 优点 | 缺点 |
|------|------|
| ✅ 保留重要信息（不丢失） | ❌ 需要额外调用 LLM（成本） |
| ✅ 节省 token（压缩率高） | ❌ 摘要有损（可能丢失细节） |
| ✅ 可定制（控制摘要内容） | ❌ 延迟增加（等待摘要生成） |

**优化建议：**
- 用便宜的小模型做摘要（qwen-turbo）
- 异步生成摘要（不阻塞对话）
- 阈值设高一点（20-30 条再摘要）

---

## 四、Embedding 语义记忆

### 4.1 核心思想

**把所有对话向量化存储，需要时用语义检索找回！**

```
用户：我对花生过敏
   ↓ (向量化)
[0.123, -0.456, ...] → 存入 Embedding Store

用户：有什么零食推荐？
   ↓ (向量化 + 检索)
检索到："花生过敏" (相似度 0.85)
   ↓
Prompt: "用户有过敏史：花生..."
   ↓
模型：「考虑到你对花生过敏，推荐...」
```

---

### 4.2 架构设计

```
用户提问
   │
   ├──────────────────┐
   │                  │
   ▼                  ▼
短期记忆          长期记忆
(最近 10 条)        (Embedding 检索)
   │                  │
   │                  │ 检索 top 3
   └────────┬─────────┘
            │
            ▼
    ┌───────────────┐
    │  拼成 Prompt   │
    │  给 LLM       │
    └───────────────┘
```

---

### 4.3 实现代码

```java
public class EmbeddingMemory {

    // 短期记忆
    private final List<ChatMessage> shortTermMemory = new ArrayList<>();

    // 长期记忆（向量存储）
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    // 添加消息
    public void add(ChatMessage message) {
        // 短期记忆
        shortTermMemory.add(message);

        // 长期记忆：向量化
        String content = String.format("[%s] %s: %s",
            timestamp, role, message.toString());
        TextSegment segment = TextSegment.from(content);
        embeddingStore.add(embeddingModel.embed(segment).content(), segment);
    }

    // 检索相关记忆
    public List<String> retrieveRelevantMemories(String query) {
        var matches = embeddingStore.findRelevant(
            embeddingModel.embed(query).content(),
            3,   // top 3
            0.6  // min similarity
        );
        return matches.stream()
            .map(EmbeddingMatch::embedded)
            .map(TextSegment::text)
            .toList();
    }
}
```

---

### 4.4 参数调优

| 参数 | 含义 | 推荐值 | 说明 |
|------|------|--------|------|
| `maxResults` | 检索多少条 | 3-5 | 太多会 dilute 注意力 |
| `minScore` | 最低相似度 | 0.5-0.7 | 太低会检索到无关内容 |
| 向量维度 | embedding 模型 | 1536 (text-embedding-v2) | 根据模型选择 |

---

### 4.5 代价分析

| 优点 | 缺点 |
|------|------|
| ✅ 精确回忆（无损） | ❌ 每次添加都要 embedding（成本） |
| ✅ 语义检索（模糊匹配） | ❌ 检索延迟（50-200ms） |
| ✅ 无限容量 | ❌ 需要持久化存储 |

**优化建议：**
- 只 embedding 用户消息（忽略助手回复）
- 批量 embedding（累积 10 条再存）
- 过滤无关内容（「你好」「谢谢」不用存）

---

## 五、用户画像持久化

### 5.1 核心思想

**把长期信息（姓名、偏好、禁忌）存到文件/数据库，跨会话不丢失！**

```
第一次会话：
用户：「我叫张三，对花生过敏」
   ↓
提取信息 → 存入 user-profiles/zhangsan.json

第二次会话（重启后）：
用户：「你还记得我吗？」
   ↓
加载画像 → 「你是张三，对花生过敏」
```

---

### 5.2 画像结构设计

```json
{
  "userId": "zhangsan",
  "attributes": {
    "name": "张三",
    "location": "杭州",
    "occupation": "程序员"
  },
  "preferences": ["篮球", "咖啡", "科幻电影"],
  "allergies": ["花生"],
  "lastUpdated": "2026-03-13T10:30:00"
}
```

---

### 5.3 实现架构

```
┌─────────────────────────────────┐
│      UserProfileStore           │
│                                 │
│  + setCurrentUser(userId)       │
│  + loadProfile(userId)          │
│  + updateProfile(key, value)    │
│  + addPreference(pref)          │
│  + addAllergy(allergy)          │
│  + getProfileDescription()      │
└─────────────────────────────────┘
              │
              │ 存储到
              ▼
┌─────────────────────────────────┐
│  user-profiles/                 │
│    ├── zhangsan.json            │
│    ├── lisi.json                │
│    └── ...                      │
└─────────────────────────────────┘
```

---

### 5.4 与对话集成

```java
// 1. 会话开始：加载画像
store.setCurrentUser("zhangsan");
String profileDesc = store.getProfileDescription();

// 2. 拼入 System Prompt
SystemMessage.from("你是一个助手。" + profileDesc);

// 3. 对话中提取新信息，更新画像
// 用户说：「我最近喜欢上了游泳」
store.addPreference("游泳");
```

---

### 5.5 生产级考虑

| 考虑 | 方案 |
|------|------|
| **存储** | 文件（小项目）/ MySQL（中）/ Redis（大） |
| **并发** | 文件锁 / 数据库事务 |
| **隐私** | 加密存储 / 脱敏处理 |
| **过期** | 定期清理未活跃用户 |

---

### 5.6 金融投顾场景生产架构（用户实战方案）

**场景背景**：金融投顾行业，服务 C 端散户，单 session 多轮问答，合规要求严格。

**架构设计**：
```
┌─────────────────────────────────────────────────────────────┐
│                    用户请求                                  │
└─────────────────────────┬───────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          │               │               │
          ▼               ▼               ▼
    ┌───────────┐  ┌───────────┐  ┌──────────────┐
    │   Redis   │  │   Redis   │  │    Kafka     │
    │ 短期记忆   │  │ 用户画像   │  │  对话日志     │
    │ TTL 30min  │  │ 长期存储   │  │  → MySQL     │
    └─────┬─────┘  └─────┬─────┘  └──────┬───────┘
          │              │                │
          └──────────────┴────────────────┘
                         │
                         ▼
              ┌─────────────────┐
              │   拼成 Prompt    │
              │   给 LLM        │
              └─────────────────┘
```

**Redis Key 命名规范**：
```bash
# 短期记忆（session 维度，TTL 30 分钟，每次对话后续期）
wencairobot:user_dialog:{user_id}:{session_id}:messages  →  List

# 用户画像（user 维度，长期存储）
wencairobot:user_profile:{user_id}:  →  Hash
# Hash 内容：{name:"张三", risk_level:"稳健型", investment_horizon:"中线", holdings:"600519,000858"}

# 对话日志（异步写入 Kafka，下游消费到 MySQL）
Kafka Topic: dialog-log
Schema: {user_id, session_id, role, content, timestamp}
```

**关键设计决策**：

| 设计点 | 方案 | 理由 |
|--------|------|------|
| **session_id 生成** | 服务端生成 | 防止客户端伪造会话 ID，窃取他人对话历史 |
| **短期记忆存储** | Redis List | 支持 range 查询，TTL 自动过期，多实例共享 |
| **TTL 策略** | 30 分钟，每次对话续期 | 无操作自动清理，节省成本；活跃会话持续保留 |
| **对话日志** | Kafka → MySQL | 异步削峰，永久存储，满足金融合规审计要求 |
| **用户画像** | Redis Hash | 按 field 维度更新，支持部分字段修改 |

**代码实现要点**（`day9/RedisBackedChatMemory.java`）：
```java
public class RedisBackedChatMemory implements ChatMemory {
    // Key 命名：wencairobot:user_dialog:{user_id}:{session_id}:messages
    private final String redisKeyPrefix;

    public RedisBackedChatMemory(String userId, String sessionId, int maxTokens) {
        this.redisKeyPrefix = "wencairobot:user_dialog:" + userId + ":" + sessionId + ":";
    }

    @Override
    public void add(ChatMessage message) {
        // 1. 加载 → 2. 添加 → 3. 裁剪 → 4. 存储 → 5. 刷新 TTL
        // 6. 异步写入 Kafka（对话日志）
        logDialogAsync(message);
    }

    private void logDialogAsync(ChatMessage message) {
        // TODO: 生产环境：kafkaProducer.send("dialog-log", ...)
    }
}
```

**合规审计要求**：
- 对话日志永久存储（MySQL）
- 支持按 user_id / session_id / 时间范围 查询
- 敏感信息脱敏（身份证号、银行卡号）
- 审计日志不可篡改（可选：区块链存证）

---

## 六、综合应用：三层记忆系统

### 6.1 架构设计

```
┌─────────────────────────────────────────────┐
│              用户提问                        │
└─────────────────┬───────────────────────────┘
                  │
      ┌───────────┼───────────┐
      │           │           │
      ▼           ▼           ▼
┌───────────┐ ┌───────────┐ ┌───────────┐
│ 短期记忆   │ │ 长期记忆   │ │ 用户画像   │
│ (最近 10 条) │ │ (Embedding)│ │ (持久化)  │
└─────┬─────┘ └─────┬─────┘ └─────┬─────┘
      │             │             │
      │             │             │
      └──────────┬──┴─────────────┘
                 │
                 ▼
       ┌─────────────────┐
       │   拼成 Prompt    │
       │   给 LLM        │
       └─────────────────┘
```

---

### 6.2 Prompt 拼接示例

```java
String buildPrompt(String userInput) {
    StringBuilder sb = new StringBuilder();

    // 1. 用户画像（最相关）
    sb.append("【用户信息】\n");
    sb.append(profileStore.getProfileDescription());
    sb.append("\n\n");

    // 2. 长期记忆（语义检索）
    sb.append("【相关历史】\n");
    for (String memory : embeddingMemory.retrieveRelevantMemories(userInput)) {
        sb.append("- ").append(memory).append("\n");
    }
    sb.append("\n");

    // 3. 短期记忆（最近对话）
    sb.append("【当前对话】\n");
    for (ChatMessage msg : shortTermMemory.getRecent(10)) {
        String role = msg instanceof UserMessage ? "用户" : "助手";
        sb.append(role).append(": ").append(msg).append("\n");
    }

    sb.append("\n助手：");
    return sb.toString();
}
```

---

### 6.3 成本估算

假设日活 1000 用户，每人 50 轮对话：

| 组件 | 单次成本 | 每日成本 |
|------|----------|----------|
| 短期记忆（Window） | 免费 | 免费 |
| 长期记忆（Embedding） | ~$0.0001/次 | ~$5/天 |
| 摘要生成 | ~$0.001/次 | ~$50/天（可选） |
| 用户画像存储 | 免费（文件） | 免费 |

**优化：** 只对 VIP 用户开启摘要和 Embedding 记忆

---

## 七、检查清单

在实现 Memory 系统前，检查以下项目：

- [ ] 选择合适的 Window 策略（Message vs Token）
- [ ] 确定 Window 大小（10-20 条 or 1000-4000 token）
- [ ] 决定是否需摘要（重要信息不能丢）
- [ ] 决定是否需 Embedding 检索（精确回忆）
- [ ] 决定是否需持久化（跨会话）
- [ ] 设计用户画像结构（attributes, preferences, allergies）
- [ ] 考虑隐私和合规（GDPR, 数据删除）
- [ ] 估算成本（embedding + 摘要调用）

---

*最后更新：2026-03-13*
