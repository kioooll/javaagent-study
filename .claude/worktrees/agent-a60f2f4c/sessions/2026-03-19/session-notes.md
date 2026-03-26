# Session Notes - 2026-03-19

## 概览
- **主题**: Day 16-17：Multi-Agent 系统 + Plan-and-Solve
- **主要内容**: Orchestrator+SubAgent 架构、Plan-and-Solve 模式、CompletableFuture 并行执行

---

## 学生初始理解
- Agent 路由：把每个 Agent 能力写在 prompt 里，LLM 通过意图判断分发 ✅
- 并行/串行：无依赖关系可以并行，有依赖必须串行 ✅

---

## 涵盖概念

### Day 16 回顾（代码已存在）
- `Orchestrator` 通过 `identifyIntent()` 用 LLM 做意图路由
- `getAllAgentsDescription()` 动态生成 Agent 能力描述注入 Prompt
- 发现问题：执行 SubAgent 的循环是串行的（day16/Orchestrator.java:94）

### Day 17：Plan-and-Solve 改造
**核心改进**：LLM 不只输出 Agent 名单，而是输出带依赖关系的执行计划（DAG）

**Prompt 设计**：学生自己写了意图识别 Prompt，包含：
- `id`：任务编号
- `agent`：Agent 名称
- `depends`：依赖的任务 id 列表

**讨论的 Prompt 细节**：
- id 用随机数有碰撞风险，改为顺序编号更稳
- depends 类型要统一（字符串数组）
- 加 "直接输出JSON数组，不要markdown代码块" 避免模型包裹代码块

**CompletableFuture 并行执行**：
```java
// 无依赖：直接并行
CompletableFuture.supplyAsync(() -> agent.handle(...), executor)

// 有依赖：等上游完成
CompletableFuture.allOf(depFutures).thenApplyAsync(v -> {
    String upstreamResults = 收集上游结果;
    return agent.handle(enrichedInput);
})
```

**上游结果传递方案**：讨论了两种
- 方案A：共享 Context（黑板模式），但跨服务不适用
- 方案B：Orchestrator 集中收结果传给下游输入 ✅ 选择此方案

**叶子节点聚合**：没有被任何任务依赖的节点 = 最终输出节点

---

## 运行结果观察
- 测试2/4：`并行执行` 日志同时出现，`串行执行` 在后 ✅
- 测试4（RAG+Data并行→Report）比测试3（Data→Report）慢约3s，符合预期
- **发现问题**：测试2 中 LLM 过度规划，用户没要求写报告但规划了 Report Agent

## 知识点掌握

| 概念 | 置信度 |
|------|--------|
| Plan-and-Solve DAG 执行计划 | 高 |
| CompletableFuture 并行/串行组合 | 高 |
| 叶子节点聚合逻辑 | 高 |
| 过度规划问题及 Prompt 防御 | 高 |
| 跨服务结果传递（MQ/Redis/集中持有）| 中高 |

---

## 知识缺口
- 无明显缺口，概念理解清晰

## 下一步
- Day 18：Agent 间通信（SubAgent 直接协作，调用链追踪防死循环）
