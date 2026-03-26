# Java Agent 学习首页

> 这是当前 Java Agent 学习笔记的 Obsidian 入口页。
> 建议先从 [[01-学习主线]] 开始，再按主题深入。

---

## 这套笔记怎么用

### 如果你是第一次系统看这套笔记
按这个顺序读：
1. [[10-Agent-基础架构]]
2. [[11-Memory-与上下文]]
3. [[12-RAG-与检索增强]]
4. [[13-Multi-Agent-与编排]]

### 如果你是回头查资料
可以直接按主题跳转：
- Agent 基础：[[10-Agent-基础架构]]
- Memory 与上下文：[[11-Memory-与上下文]]
- RAG 与检索增强：[[12-RAG-与检索增强]]
- Multi-Agent 与编排：[[13-Multi-Agent-与编排]]

### 如果你想回看最原始的学习记录
- 学习进度总览：[[progress/java-agent-tracker]]
- 架构原文：[[progress/java-agent-architecture]]
- Memory 原文：[[progress/memory-system-design]]
- RAG 原文：[[progress/rag-best-practices]]

---

## 笔记结构

### 导航页
- [[00-JavaAgent-首页]]：总入口
- [[01-学习主线]]：推荐阅读路径

### 基础主题
- [[10-Agent-基础架构]]：Agent 公式、ReAct、Tool 设计、Function Calling
- [[11-Memory-与上下文]]：短期/长期记忆、Redis 持久化、摘要与上下文管理

### 进阶主题
- [[12-RAG-与检索增强]]：切块、overlap、混合检索、RRF、评估与防注入
- [[13-Multi-Agent-与编排]]：多 Agent 分工、并行执行、DAG 编排、错误传播

### 归档
- [[progress/java-agent-tracker]]：总进度与阶段轨迹
- `progress/`：原始学习记录与专题长文
- `sessions/`：阶段性学习过程笔记

---

## 当前知识地图

这批笔记目前主要覆盖 4 个层面：

1. **Agent 基础架构**
   - Agent = LLM + Planning + Memory + Tools
   - ReAct 循环
   - Tool 抽象与 Function Calling

2. **Memory 与上下文**
   - 短期记忆与长期记忆分层
   - Redis 持久化短期记忆
   - 摘要策略与上下文窗口控制

3. **RAG 与检索增强**
   - 文档切块与 overlap
   - FAQ / 表格 / 代码文档处理
   - BM25 + 向量检索 + RRF 融合

4. **Multi-Agent 与编排**
   - Orchestrator / SubAgent 分工
   - CompletableFuture 并行与异步链路
   - DAG 错误传播与并发调优

---

## 当前最值得继续深挖的方向

1. **Core Java 与并发基础如何补齐到能支撑 Agent 编排实现**
2. **Tooling 层怎样从“会调工具”进一步走向稳定工程抽象**
3. **Observability / Safety 如何补进生产级 Agent 系统**
4. **Multi-Agent 链路里的超时、重试、降级与资源边界如何工程化**

---

## 快速入口

- 从头系统看：[[01-学习主线]]
- 查 Agent 基础：[[10-Agent-基础架构]]
- 查 Memory：[[11-Memory-与上下文]]
- 查 RAG：[[12-RAG-与检索增强]]
- 查编排：[[13-Multi-Agent-与编排]]
- 查原始进度：[[progress/java-agent-tracker]]
