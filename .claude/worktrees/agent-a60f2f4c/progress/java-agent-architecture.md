# Java Agent 核心架构经验总结

> 📝 记录 Java Agent 开发中的核心概念、架构设计和最佳实践

---

## 一、Agent 核心概念

### 1.1 Agent 公式

```
Agent = LLM (大脑) + Planning (规划) + Memory (记忆) + Tools (工具)
```

| 组件 | 作用 | 类比 |
|------|------|------|
| **LLM** | 理解和决策 | 大脑 |
| **Planning** | 分解任务、制定策略 | 思考过程 |
| **Memory** | 存储历史和上下文 | 短期/长期记忆 |
| **Tools** | 执行具体操作 | 手脚 |

---

### 1.2 ReAct 模式

**ReAct = Reason + Act（推理 + 行动）**

```
┌─────────────┐
│  Problem    │ 用户输入
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ Thought     │ 思考：我需要做什么？
│ (推理)      │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ Action      │ 行动：调用工具
│ (执行)      │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ Observation │ 观察：工具返回什么结果？
│ (结果)      │
└─────┬───────┘
      │
      ▼
   [循环直到问题解决]
```

**关键点：**
- 每次循环只做一件事（一个工具调用）
- 必须有终止条件（maxIterations 或 空 Action）
- 历史上下文必须传递给下一轮

---

## 二、手写 Agent 架构设计

### 2.1 核心类设计

```
┌─────────────────────────────────────────┐
│              Agent                      │
│  - callLlm()                            │
│  - agent() ← 主循环                     │
│  - doSummary() ← 超出轮数时总结          │
└─────────────────────────────────────────┘
                    │
                    │ 使用
                    ▼
┌─────────────────────────────────────────┐
│           AgentContext                  │
│  - List<Message> messages ← 会话历史    │
│  - addMessage()                         │
└─────────────────────────────────────────┘
                    │
                    │ 调用
                    ▼
┌─────────────────────────────────────────┐
│            Tool<I, O>                   │
│  + execute(String): String              │
│  + getToolPrompt(): String              │
│  - convert2Input(String): I             │
│  - doExecute(I): O                      │
│  - convert2Output(O): String            │
└─────────────────────────────────────────┘
```

---

### 2.2 Tool 接口设计（模板方法模式）

```java
public abstract class Tool<I, O> {

    // 模板方法：定义执行流程
    public String execute(String input) {
        I typedInput = convert2Input(input);      // 1. 参数解析
        O result = doExecute(typedInput);         // 2. 执行逻辑
        return convert2Output(result);            // 3. 结果格式化
    }

    // 子类实现具体逻辑
    protected abstract I convert2Input(String input);
    protected abstract O doExecute(I input);
    protected abstract String convert2Output(O result);

    // 工具自描述：用于生成 Prompt
    public abstract String getToolPrompt();
}
```

**设计要点：**
- **职责分离**：输入解析、业务逻辑、输出格式化各司其职
- **自描述**：`getToolPrompt()` 返回工具的 JSON Schema，Agent 不需要知道内部实现
- **泛型设计**：`<I, O>` 保证类型安全

---

### 2.3 AgentContext 设计（状态模式）

```java
public class AgentContext {
    // 会话历史（替代无限增长的 List<Message>）
    private List<Message> messages = new ArrayList<>();

    // 或者手写 ReAct 版本：存储完整推理链
    private List<ThoughtAndActionInfo> history = new ArrayList<>();

    public void addMessage(Role role, String content) {
        messages.add(new Message(role, content));
    }

    // 获取完整历史供 LLM 使用
    public List<Message> getMessages() {
        return messages;
    }
}
```

**为什么需要 Context？**
- 多轮对话需要记住历史
- Tool 调用结果需要回传给 LLM
- 超出轮数时需要基于历史做总结

---

### 2.4 Agent 主循环（手写版）

```java
public String agent(String userInput) {
    // 初始化
    if (context == null) {
        context = new AgentContext();
        context.addMessage(Role.USER, userInput);
    }

    // 主循环
    for (int round = 1; round <= maxRounds; round++) {
        // 1. 调用 LLM
        Message response = callLlm(context.getMessages(), tools);

        // 2. 检查是否超出轮数
        if (round == maxRounds) {
            return doSummary(context.getMessages());
        }

        // 3. 解析工具调用
        List<ToolCall> toolCalls = response.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            // 没有工具调用，直接返回
            return response.getContent();
        }

        // 4. 执行工具
        for (ToolCall tc : toolCalls) {
            String result = executeTool(tc);
            context.addMessage(Role.TOOL, result);
        }
    }

    return doSummary(context.getMessages());
}
```

---

## 三、Function Calling vs 手写 ReAct

### 3.1 本质区别

| 方面 | 手写 ReAct | Function Calling |
|------|-----------|------------------|
| **循环结构** | 不变（Thought→Action→Observation） | 不变 |
| **工具协议** | 自定义字符串格式 | 标准化 JSON Schema |
| **输出解析** | `split()`, `indexOf()` 脆弱 | `getToolCalls()` 结构化 |
| **Prompt 管理** | 手动拼接字符串 | `List<Message>` 自动管理 |
| **Role 语义** | 全部塞进 USER | TOOL role 明确标识结果 |

---

### 3.2 Function Calling 核心改动

**1. Tool 定义：添加 JSON Schema**
```java
public String getToolParamDesc() {
    JsonObject params = new JsonObject();
    params.addProperty("type", "object");

    JsonObject properties = new JsonObject();

    JsonObject city = new JsonObject();
    city.addProperty("type", "string");
    city.addProperty("description", "城市名，如'杭州'");
    properties.add("city", city);

    params.add("properties", properties);
    params.add("required", new JsonArray() {{ add("city"); }});

    return params.toString();
}
```

**2. AgentContext：用 `List<Message>` 管理历史**
```java
// 不再重建 Prompt 字符串
context.addMessage(Role.USER, userInput);
context.addMessage(Role.ASSISTANT, llmResponse);
context.addMessage(Role.TOOL, toolResult);  // ← 关键：TOOL role
```

**3. Agent：调用 API**
```java
// 旧版：手动拼接 Prompt
String prompt = buildPrompt(history);
String response = callLlm(prompt);
ToolCall tc = parseToolCall(response);  // split, indexOf...

// 新版：结构化调用
GenerationOptions options = GenerationOptions.builder()
    .tools(tools)
    .build();
Message response = model.generate(messages, options);
List<ToolCall> toolCalls = response.getToolCalls();  // ← 结构化结果
```

---

### 3.3 Role 的重要性

**手写 ReAct 的问题：**
```
所有历史都塞进 USER 消息：
- "今天杭州天气" → "Action: 天气查询" → "Observation: 26°C" → "那北京呢？"

模型困惑：这是新的请求？还是继续上一轮？
```

**Function Calling 的解决：**
```
USER: 今天杭州天气
ASSISTANT: [ToolCall: weather(city="杭州")]
TOOL: 26°C
ASSISTANT: 今天杭州的天气是 26 摄氏度。
USER: 那北京呢？

→ 模型清楚知道：TOOL 是上轮结果，USER 是新请求
```

**结论：** Role 不只是格式，是模型理解对话状态的信号。

---

## 四、LangChain4j 核心抽象

### 4.1 AiServices：标准化 Agent 工作流

```java
// 手写版
Agent agent = new Agent();
agent.registerTool(new WeatherTool());
String response = agent.process("今天杭州天气");

// LangChain4j 版
Assistant assistant = AiServices.builder(Assistant.class)
    .chatLanguageModel(model)
    .tools(new WeatherTool())
    .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
    .build();
String response = assistant.chat("今天杭州天气");
```

**AiServices 做了什么？**
- `chatLanguageModel` → 对应手写的 `callLlm()`
- `tools` → 对应手写的 `registerTool()`
- `chatMemory` → 对应手写的 `List<Message>`

---

### 4.2 @Tool 注解

```java
public class WeatherTool {

    @Tool("查询指定城市指定日期的天气")
    public String queryWeather(
        @P("城市名称") String city,
        @P("日期，格式 yyyy-MM-dd") String date
    ) {
        // ...业务逻辑
        return "晴，26°C";
    }
}
```

**优点：**
- 简洁：一个注解定义工具
- 自动：参数名和描述自动生成 Schema

**局限：**
- 无法表达 `required` / `optional`
- 复杂嵌套结构（Object 里有 Object）难以表达
- 动态 Schema（参数依赖运行时数据）无法支持

**生产建议：**
- 简单参数：用 `@Tool` + `@P`
- 复杂参数：手写 `ToolSpecification` + `JsonObject`

---

### 4.3 ChatMemory：会话记忆策略

| 实现 | 策略 | 适用场景 |
|------|------|----------|
| `MessageWindowChatMemory` | 保留最近 N 条消息 | 通用推荐 |
| `TokenWindowChatMemory` | 保留最近 N 个 token | 严格控制成本 |
| 无（Stateless） | 不保留历史 | 单轮问答 |

**为什么需要裁剪？**
- 无限增长 → token 消耗大 → 成本高
- 历史太长 → 模型注意力分散 → 质量下降

---

## 五、Human-in-the-loop（人机协同）

### 5.1 干预场景

| 场景 | 干预时机 | 示例 |
|------|----------|------|
| **执行前拦截** | 工具调用前 | 高风险操作确认（y/n） |
| **输出后拦截** | 模型回复前 | 敏感词过滤、合规审查 |
| **强制指定工具** | 模型判断不准 | 兜底策略 |

---

### 5.2 方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **塞进 @Tool** | 简单 | 不灵活、不通用、职责混乱 | ⭐⭐ |
| **手写循环** | 灵活、职责清晰 | 代码量大 | ⭐⭐⭐⭐⭐ |
| **装饰器/代理** | 理论上优雅 | `@Tool` 无法继承/代理 | ❌ 不可行 |

---

### 5.3 生产级实现：ConfirmService 注入

```java
// 独立的确认服务
public class ConfirmService {
    public boolean confirm(String action, String params) {
        System.out.println("确认执行：" + action + "，参数：" + params);
        System.out.print("输入 y 确认，n 拒绝：");
        String input = new Scanner(System.in).nextLine();
        return "y".equalsIgnoreCase(input);
    }
}

// Tool 中注入并使用
public class WeatherTool {
    @Inject
    private ConfirmService confirmService;

    @Tool("查询天气")
    public String queryWeather(String city) {
        // 高风险操作前确认
        if (!confirmService.confirm("weather_query", city)) {
            return "用户拒绝了天气查询请求。请询问用户是否继续。";
            // ↑ 关键：return 指令字符串给模型，而不是抛异常
        }

        // 执行查询...
    }
}
```

**关键点：**
- **职责分离**：ConfirmService 只管确认，WeatherTool 只管查询
- **return 指令**：被拒绝时 return 明确指令，模型会理解并处理
- **不要抛异常**：异常会导致模型"编造"信息来圆场

---

## 六、ChatModelListener：调试利器

### 6.1 接口定义

```java
public interface ChatModelListener {
    default void onRequest(ChatRequest request) {}
    default void onResponse(ChatResponse response) {}
    default void onError(Throwable error) {}
}
```

### 6.2 实现示例

```java
public class LoggingListener implements ChatModelListener {

    @Override
    public void onRequest(ChatRequest request) {
        System.out.println("=== LLM 请求 ===");
        for (Message msg : request.messages()) {
            System.out.println(msg.role() + ": " + msg.content());
        }
        System.out.println("Tools: " + request.toolSpecifications());
    }

    @Override
    public void onResponse(ChatResponse response) {
        System.out.println("=== LLM 响应 ===");
        System.out.println("Content: " + response.content().text());
        System.out.println("ToolCalls: " + response.toolCalls());
    }
}

// 注册
QwenChatModel model = QwenChatModel.builder()
    .apiKey(apiKey)
    .listeners(List.of(new LoggingListener()))
    .build();
```

---

## 七、架构决策总结

### 7.1 AiServices vs 手写循环

| 维度 | AiServices | 手写循环 |
|------|-----------|----------|
| **开发效率** | 高（~30 行） | 低（~200 行） |
| **定制能力** | 低（固定流程） | 高（任意控制） |
| **调试难度** | 中（黑盒） | 低（完全透明） |
| **适用场景** | 标准问答 | 复杂干预、多 Agent |

**决策树：**
```
需要复杂人机交互吗？
├─ 否 → AiServices
└─ 是 → 手写循环
```

---

### 7.2 @Tool 注解 vs 手写 Schema

| 维度 | @Tool 注解 | 手写 Schema |
|------|-----------|------------|
| **简洁度** | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| **表达能力** | ⭐⭐ | ⭐⭐⭐⭐⭐ |
| **类型安全** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **适用场景** | 简单参数 | 嵌套/动态参数 |

**决策树：**
```
参数有嵌套结构/动态约束吗？
├─ 否 → @Tool 注解
└─ 是 → 手写 JsonObject Schema
```

---

### 7.3 Tool 返回值设计

**❌ 错误做法：抛异常**
```java
if (!confirmed) {
    throw new PermissionDeniedException("用户拒绝");
}
// → 模型会"编造"信息来圆场
```

**✅ 正确做法：return 指令**
```java
if (!confirmed) {
    return "用户拒绝了请求。请礼貌地解释需要确认才能执行。";
}
// → 模型理解这是指令，会按指令回复
```

---

## 八、常见坑及解决方案

### 8.1 模型无限循环调用工具

**原因：** Prompt 没告诉模型什么时候停止

**解决：**
```
【重要规则】
- 如果已经有足够信息回答问题，请直接回答
- 如果工具调用失败超过 3 次，请告诉用户你无法完成
- 每次只调用一个工具
```

---

### 8.2 字符串解析崩溃

**症状：** `StringIndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`

**原因：** 模型输出格式偏离预期

**解决：**
```java
// ❌ 脆弱解析
String[] parts = line.split(",");
String name = parts[0].substring(parts[0].indexOf(":"));

// ✅ 结构化解析
List<ToolCall> toolCalls = response.getToolCalls();
if (toolCalls == null) return null;
// → Function Calling 保证格式正确
```

---

### 8.3 超出轮数时返回空

**原因：** 没有处理边界情况

**解决：**
```java
if (round >= maxRounds) {
    return doSummary(context.getMessages());
    // ↑ 不传 tools，让模型根据历史做总结
}
```

---

## 九、检查清单

在上线 Agent 系统前，检查以下项目：

- [ ] ReAct 循环终止条件明确（maxIterations + 空 Action）
- [ ] Tool 接口自描述（`getToolPrompt()` 或 `ToolSpecification`）
- [ ] 会话历史用 `List<Message>` 管理（不用拼接字符串）
- [ ] Role 使用正确（USER/ASSISTANT/TOOL）
- [ ] Human-in-the-loop 用 return 指令而非抛异常
- [ ] 有 ChatModelListener 调试输入输出
- [ ] 超出轮数时有兜底处理（doSummary）
- [ ] Prompt 包含安全规则（防御注入攻击）

---

*最后更新：2026-03-13*
