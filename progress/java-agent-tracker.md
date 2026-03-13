# Java Agent 学习进度追踪

**最后更新**: 2026-03-14 (生产级 Memory 架构 - Redis 持久化)

---

## 快速统计

| 指标 | 数值 |
|------|------|
| 总进度 | ~15% |
| 已学习天数 | 3 |
| 主题掌握 | 4/6 领域有进展 |
| 高优先级知识缺口 | 1 |

---

## 领域进度总览

| 领域 | 已学/主题数 | 状态 |
|------|------------|------|
| A. Core Java & Concurrency | 0/6 | ⬜ 未开始 |
| B. Agent Frameworks | 2/6 | 🟡 进行中 |
| C. Memory & Context | 4/5 | 🟢 已完成 |
| D. RAG | 2/6 | 🟡 进行中 |
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

- **LangChain4j 核心抽象** (2026-03-13, 置信度: 高)
  - `AiServices`：标准化 Agent 工作流，映射到手写的 callLlm/registerTool/List<Message>
  - `@Tool` + `@P`：注解替代手写 Schema，局限：无法精确控制 required/optional
  - `ChatMemory`：MessageWindowChatMemory（按条数）/ TokenWindowChatMemory（按 token）
  - Human-in-the-loop：复杂干预场景应手写循环而非塞进 @Tool，职责分离
  - AiServices 适合标准场景，手写循环适合需要精细控制的场景
  - Human-in-the-loop：@Tool 注解无法继承/动态代理，生产方案是 ConfirmService 注入
  - ChatModelListener：onRequest/onResponse/onError，注册到 model.listeners() 调试
  - Tool 拒绝时应 return 指令字符串而非抛异常，避免模型编造信息

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
- [ ] **Prompt 工程** - 示例污染、格式约束、模型行为调优（2026-03-12 新增）

### 中优先级
- [ ] 框架选型（LangChain4j vs Spring AI）
- [ ] 环境搭建

### 低优先级
- [ ] 暂无

---

## 最近解决
*暂无*

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

### 阶段二：RAG 生产级（目标：能构建企业级知识库问答）
- [ ] Day 8: 真实文档加载（PDF/TXT）+ 切块策略对比
- [ ] Day 9: 持久化向量存储（PGVector 或 Redis）
- [ ] Day 10: 混合检索（BM25 + 向量）+ 元数据过滤
- [ ] Day 11: Re-ranking 策略
- [ ] Day 12: RAG 效果评估（准确率、召回率、幻觉检测）

---

### 阶段三：Memory 深入（目标：构建有长期记忆的 Agent）
- [x] Day 13: TokenWindowChatMemory + 对话摘要策略
- [x] Day 14: 长期记忆设计（用户画像持久化、跨会话记忆）
- [x] Day 15: Embedding 记忆（语义检索历史对话）
- [x] Day 15+: 生产级 Memory 架构（Redis 持久化短期记忆，多实例共享）
- [x] Day 15++: 金融投顾场景生产方案（服务端生成 session_id、Kafka 异步日志、合规审计）

---

### 阶段四：Multi-Agent（目标：能设计多智能体协作系统）
- [ ] Day 16: 多 Agent 概念（Orchestrator + SubAgent 模式）
- [ ] Day 17: 手写 Orchestrator，任务分发给专业 SubAgent
- [ ] Day 18: Agent 间通信 + 结果聚合
- [ ] Day 19: Plan-and-Solve 模式（先规划再执行）
- [ ] Day 20: 并发多 Agent（CompletableFuture 并行调用）

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
