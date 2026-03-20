# Java Agent 学习进度追踪

**最后更新**: 2026-03-20 (DAG 错误传播处理 - exceptionally() 优雅降级)

---

## 快速统计

| 指标 | 数值 |
|------|------|
| 总进度 | ~30% |
| 已学习天数 | 5 |
| 主题掌握 | 5/6 领域有进展 |
| 高优先级知识缺口 | 0 |

---

## 领域进度总览

| 领域 | 已学/主题数 | 状态 |
|------|------------|------|
| A. Core Java & Concurrency | 0/6 | ⬜ 未开始 |
| B. Agent Frameworks | 5/6 | 🟢 进行中 |
| C. Memory & Context | 4/5 | 🟢 已完成 |
| D. RAG | 6/6 | 🟢 已完成 |
| E. Tooling | 0/6 | ⬜ 未开始 |
| F. Observability & Safety | 0/6 | ⬜ 未开始 |

---

## 已掌握主题

### B. Agent Frameworks
- **ReAct 循环架构** (2026-03-12, 置信度: 高)
  - 能独立设计 Thought→Action→Observation 循环
  - 理解终止条件设计（maxIterations + 空 Action）
  - 实现了递归式 Agent loop

- **Tool 接口设计** (2026-03-12, 置信度: 高)
  - 模板方法模式：`convert2Input → doExecute → convert2Output(I,O)`
  - 工具自描述（`getToolPrompt()` 返回 `ToolFunction`），Agent 与 Tool 解耦
  - WeatherTool 完整实现（含 JsonObject 参数 Schema，Fastjson 反序列化）

- **Function Calling** (2026-03-13, 置信度: 高)
  - 理解 FC 与手写 ReAct 的本质区别：工具协议标准化，循环结构不变
  - `GenerationParam.tools()` 传入工具定义，`response.getToolCalls()` 取结构化结果
  - `List<Message>` 维护会话历史，`Role.TOOL` 回传工具结果
  - 超轮数边界：`doSummary()` 不传 tools 让模型根据历史做总结
  - Role 不只是格式，是模型理解对话状态的信号

- **DAG 错误传播处理** (2026-03-20, 置信度: 中高)
  - `exceptionally()` 捕获 SubAgent 异常，返回 TASK_ERROR fallback
  - CompletionException 包装层：真正原因在 `e.getCause()`
  - 错误内容透传给下游 Agent，模型写出"诚实报告"
  - `aggregateResults()` 汇总失败任务，叶子全败时让模型生成用户友好解释
  - 错误策略选型：fail-fast / 部分成功 / 降级 三者权衡

- **LangChain4j 核心抽象** (2026-03-13, 置信度: 高)
  - `AiServices`：标准化 Agent 工作流，映射到手写的 callLlm/registerTool/List<Message>
  - `@Tool` + `@P`：注解替代手写 Schema，局限：无法精确控制 required/optional
  - `ChatMemory`：MessageWindowChatMemory（按条数）/ TokenWindowChatMemory（按 token）
  - Human-in-the-loop：复杂干预场景应手写循环而非塞进 @Tool，职责分离
  - AiServices 适合标准场景，手写循环适合需要精细控制的场景
  - Human-in-the-loop：@Tool 注解无法继承/动态代理，生产方案是 ConfirmService 注入
  - ChatModelListener：onRequest/onResponse/onError，注册到 model.listeners() 调试
  - Tool 拒绝时应 return 指令字符串而非抛异常，避免模型编造信息

### D. RAG
- **文档加载与切块策略** (2026-03-16, 置信度：高)
  - `FileSystemDocumentLoader` 加载真实 PDF/TXT 文档
  - 三种切块策略对比：固定字符数 vs 按段落 vs 递归切分
  - 理解重叠切分（overlap）保持上下文的重要性
  - 向量存储 JSON 持久化（`serializeToFile` / `fromFile`）

- **PGVector 持久化** (2026-03-16, 置信度：高)
  - 生产级向量数据库方案（替代内存库）
  - 支持 SQL 查询能力（按部门/时间/权限过滤）
  - 适用于百万级向量场景
  - 需要 PostgreSQL 9.6+ 和 pgvector 插件

- **混合检索 + RRF 融合** (2026-03-16, 置信度：高)
  - BM25 关键词检索 + 向量语义检索结合
  - RRF (Reciprocal Rank Fusion) 倒数排名融合算法
  - 理解 k 参数（通常=60）对融合权重的影响
  - 解决纯向量检索匹配不到专有名词的问题

- **FAQ 文档处理** (2026-03-16, 置信度：高)
  - 按 Q&A 对切分（保持问答语义完整）
  - 正则解析 FAQ 格式（`Q:/A:` 或 `问题:/答案:`）
  - 特殊格式化处理提升向量化效果

- **RAG 效果评估** (2026-03-16, 置信度：高)
  - 检索准确率 (Context Recall) - 检索内容是否相关
  - 回答忠实度 (Faithfulness) - 是否编造信息
  - 回答相关性 (Answer Relevance) - 是否解决问题
  - 用 LLM 当裁判的评估策略（JSON 格式评分）

### C. Memory & Context
- **AgentContext 多轮历史** (2026-03-12, 置信度: 中高)
  - `List<ThoughtAndActionInfo>` 存储完整推理链
  - 每轮记录：思考文本 + 工具调用参数 + 工具结果
  - 历史拼入 Prompt 供下轮 LLM 使用

- **Redis 持久化短期记忆** (2026-03-14, 置信度：高)
  - 生产环境多实例部署必须用 Redis 集中存储短期记忆
  - Redis 数据结构：session:{id}:messages (List), TTL 30 分钟
  - 解决单机内存重启丢失/多实例无法共享的问题

---

## 知识缺口

### 高优先级
- [x] **Agent 核心架构理解** - ReAct 循环、Tool 调用机制（已解决 2026-03-12）
- [x] **Memory 设计** - 多轮历史 AgentContext 已实现（已解决 2026-03-12）
- [x] **Tool 接口定义** - 模板方法模式完整实现（已解决 2026-03-12）
- [x] **LLM 输出解析稳定性** - Function Calling 替代字符串解析，已解决（2026-03-13）
- [x] **Prompt 工程** - 示例污染、格式约束、模型行为调优（已解决 2026-03-16）

### 中优先级
- [ ] 框架选型（LangChain4j vs Spring AI）
- [ ] 环境搭建

### 低优先级
- [ ] 暂无

---

## 最近解决
- **文档加载与切块策略** - FileSystemDocumentLoader + DocumentSplitters 递归切分（2026-03-16）
- **PGVector 持久化** - 生产级向量数据库方案（2026-03-16）
- **混合检索 + RRF** - BM25+ 向量检索 + 倒数排名融合算法（2026-03-16）
- **FAQ 文档处理** - Q&A 对切分策略（2026-03-16）
- **RAG 效果评估** - 检索准确率/忠实度/相关性三维度评估（2026-03-16）

---

## 学习计划

**预计开始日期**: 2026-03-12
**当前阶段**: 基础已通，进入生产级技能

---

### 阶段一：入门（已完成）
- [x] Day 1-2: Agent 核心概念 + 手写 ReAct + 百炼 API
- [x] Day 3: Function Calling 改造
- [x] Day 4-6: LangChain4j 核心抽象 + Human-in-the-loop + Listener
- [x] Day 7: RAG 基础链路跑通

---

### 阶段二：RAG 生产级（✅ 已完成）
- [x] Day 8: 真实文档加载（PDF/TXT）+ 切块策略对比
- [x] Day 9: 持久化向量存储（PGVector 或 Redis）
- [x] Day 10: 混合检索（BM25 + 向量）+ 元数据过滤
- [x] Day 11: Re-ranking 策略 (RRF 融合算法)
- [x] Day 12: RAG 效果评估（准确率、召回率、幻觉检测）

---

### 阶段三：Memory 深入（✅ 已完成）
- [x] Day 13: TokenWindowChatMemory + 对话摘要策略
- [x] Day 14: 长期记忆设计（用户画像持久化、跨会话记忆）
- [x] Day 15: Embedding 记忆（语义检索历史对话）
- [x] Day 15+: 生产级 Memory 架构（Redis 持久化短期记忆，多实例共享）
- [x] Day 15++: 金融投顾场景生产方案（服务端生成 session_id、Kafka 异步日志、合规审计）

---

### 阶段四：Multi-Agent（进行中）
- [x] Day 16: 多 Agent 概念（Orchestrator + SubAgent 模式）
- [x] Day 17: Plan-and-Solve + CompletableFuture 并行执行
- [x] Day 18: Agent 间通信（SubAgent 直接协作，调用链追踪防死循环）
- [x] Day 19: 复杂 DAG 编排 + 错误传播处理
- [ ] Day 20: 并发多 Agent 性能调优

---

### 阶段五：Core Java 并发（目标：写出高性能 Agent）
- [ ] Day 21: Virtual Threads（Project Loom）+ LLM 调用并发模型
- [ ] Day 22: 异步 Agent（CompletableFuture 链式调用）
- [ ] Day 23: Streaming 响应（SSE + Reactive Streams）
- [ ] Day 24: Resilience4j 重试 + 熔断（LLM 调用容错）
- [ ] Day 25: Context 大对象的内存管理 + 序列化

---

### 阶段六：Observability & 生产安全（目标：能上生产）
- [ ] Day 26: OpenTelemetry Tracing（追踪每一步 Agent 推理）
- [ ] Day 27: Prompt Injection 防御策略
- [ ] Day 28: 输出验证 + Guardrails（结构化输出校验）
- [ ] Day 29: 非确定性输出的单元测试策略
- [ ] Day 30: Token 成本监控 + 限流

---

### 阶段七：Capstone 项目（目标：整合所有技能）
- [ ] Day 31-40: 构建一个生产级 Java Agent 系统
  - 多 Agent 协作（Orchestrator + RAG Agent + Tool Agent）
  - 持久化向量存储 + 长期记忆
  - 完整 Tracing + 成本监控
  - Prompt Injection 防御
  - 单元测试覆盖

---

## 学习日志

| 日期 | 内容 | 备注 |
|------|------|------|
| 2026-03-12 | Agent 概念入门 | 资深 Java 背景，AI 新手 |
| 2026-03-12 | 手写 ReAct Agent + 百炼 API 接入 | Tool/Agent/AgentContext 完整实现，跑通 |
| 2026-03-13 | Function Calling 改造 Agent | 替换字符串解析，List<Message> 会话历史 |
| 2026-03-13 | LangChain4j 核心概念 | AiServices/@Tool/ChatMemory/Human-in-the-loop |
| 2026-03-13 | LangChain4j 环境搭建 + day5 跑通 | 0.36.2，阿里云镜像，~30行代码完成 Agent |
| 2026-03-13 | ChatMemory + Human-in-the-loop + Listener | ConfirmService 拦截，ChatModelListener 调试 |
| 2026-03-13 | RAG 基础：索引+检索+切块策略 | InMemoryEmbeddingStore + QwenEmbedding，day7 跑通 |
| 2026-03-14 | 生产级 Memory 架构 | Redis 持久化短期记忆，多实例共享 |
| 2026-03-14 | 金融投顾场景生产方案 | 服务端生成 session_id、Kafka 异步日志、合规审计 |
| 2026-03-16 | RAG 生产级技能完整掌握 | 文档加载/切块/PGVector/混合检索/RRF/评估 |
| 2026-03-19 | Plan-and-Solve Multi-Agent | DAG 执行计划/CompletableFuture 并行/上游结果传递/过度规划防御 |
| 2026-03-20 | Agent 间通信 + 调用链防死循环 | callChain/depth 字段设计/forward()语义/safeHandle()拦截/环检测+深度检测 |
| 2026-03-20 | DAG 错误传播处理 | exceptionally() 降级/CompletionException包装层/错误透传下游/聚合时错误摘要 |
