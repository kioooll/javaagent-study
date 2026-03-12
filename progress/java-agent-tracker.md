# Java Agent 学习进度追踪

**最后更新**: 2026-03-12

---

## 快速统计

| 指标 | 数值 |
|------|------|
| 总进度 | ~8% |
| 已学习天数 | 2 |
| 主题掌握 | 3/6 领域有进展 |
| 高优先级知识缺口 | 3 |

---

## 领域进度总览

| 领域 | 已学/主题数 | 状态 |
|------|------------|------|
| A. Core Java & Concurrency | 0/6 | ⬜ 未开始 |
| B. Agent Frameworks | 0/6 | ⬜ 未开始 |
| C. Memory & Context | 0/5 | ⬜ 未开始 |
| D. RAG | 0/6 | ⬜ 未开始 |
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
  - 工具自描述（`getToolPrompt()`），Agent 与 Tool 解耦
  - WeatherTool 完整实现（Fastjson 反序列化）

### C. Memory & Context
- **AgentContext 多轮历史** (2026-03-12, 置信度: 中高)
  - `List<ThoughtAndActionInfo>` 存储完整推理链
  - 每轮记录：思考文本 + 工具调用参数 + 工具结果
  - 历史拼入 Prompt 供下轮 LLM 使用

---

## 知识缺口

### 高优先级
- [x] **Agent 核心架构理解** - ReAct 循环、Tool 调用机制（已解决 2026-03-12）
- [x] **Memory 设计** - 多轮历史 AgentContext 已实现（已解决 2026-03-12）
- [x] **Tool 接口定义** - 模板方法模式完整实现（已解决 2026-03-12）
- [ ] **LLM 输出解析稳定性** - 字符串 split 脆弱，需结构化输出/JSON（2026-03-12 新增）
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
**当前阶段**: 概念入门

### 第 1 周：基础概念 + 环境搭建
- [x] Day 1: Agent 核心概念（2026-03-12）
- [x] Day 2: 手写极简 Agent + 接入百炼 API（2026-03-12）
- [ ] Day 3: 环境搭建（Maven/Gradle, 依赖配置）
- [ ] Day 4: LangChain4j Hello World
- [ ] Day 5: Tool 定义和调用

### 第 2 周：核心功能
- [ ] 第 6-8 天：Memory 实现
- [ ] 第 9-10 天：RAG 基础

### 第 3-4 周：进阶主题
- [ ] 多 Agent 协作
- [ ] 观测性和测试

---

## 学习日志

| 日期 | 内容 | 备注 |
|------|------|------|
| 2026-03-12 | Agent 概念入门 | 资深 Java 背景，AI 新手 |
| 2026-03-12 | 手写 ReAct Agent + 百炼 API 接入 | Tool/Agent/AgentContext 完整实现，跑通 |
