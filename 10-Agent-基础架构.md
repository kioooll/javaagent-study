# Agent 基础架构

返回入口：[[00-JavaAgent-首页]]

相关原始资料：[[progress/java-agent-architecture]] ｜ [[progress/java-agent-tracker]]

---

## 一句话理解
Agent 不是一次性问答，而是围绕目标持续进行“理解 → 决策 → 调工具 → 吸收结果”的循环系统。

## 核心概念
- **Agent = LLM + Planning + Memory + Tools**
  - LLM 负责理解与决策。
  - Planning 负责拆解任务与决定下一步。
  - Memory 负责保留历史上下文。
  - Tools 负责把“会说”变成“能做”。

- **ReAct 循环是基础骨架**
  - 典型流程是 `Thought → Action → Observation`。
  - 模型先判断要做什么，再调用工具，再根据结果继续推理。
  - 没有这个循环，很多复杂任务就只能停留在单轮回答层面。

- **Tool 接口要清晰、自描述**
  - Tool 的重点不只是“能执行”，还要能告诉模型：自己做什么、收什么参数、返回什么结果。
  - 模板方法模式把“参数解析 / 执行业务 / 输出格式化”分开，能降低耦合。

- **AgentContext 负责状态承接**
  - 它保存消息历史、工具结果和当前任务状态。
  - 没有上下文层，Agent 就很难做多轮推理、回看历史或在超轮数时做总结。

- **Function Calling 让工具协议更稳定**
  - 它把工具调用变成结构化协议，而不是依赖脆弱的字符串解析。
  - `getToolCalls()` 这类结构化结果，比 `split()`、`indexOf()` 这类手写解析更可靠。

## 关键工程结论
- Agent 的稳定性很大程度上来自**工具协议和上下文管理**，不只是来自模型本身。
- Function Calling 解决的是**工具调用标准化**，不是把 Agent 循环本身消掉。
- Tool 自描述、角色化消息和清晰的上下文边界，会显著降低系统脆弱性。

## 继续阅读
- [[11-Memory-与上下文]]
- [[13-Multi-Agent-与编排]]
