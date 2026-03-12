# 2026-03-12 学习 session

## Session 概述
- **日期**: 2026-03-12
- **主题**: Java Agent 基础概念入门
- **学生背景**: 资深 Java 开发者，AI/LLM 零经验

## 学习内容

### 学生初始理解
- 对 Agent 概念完全陌生
- 直觉认为 Agent 应该有 `execute` 方法来执行请求

### 讲解的概念
1. Agent 核心公式：Agent = LLM (大脑) + Planning + Memory + Tools
2. ReAct 模式工作流程：Thought → Action → Observation 循环
3. 用设计模式类比理解 Agent:
   - Command 模式（Tool）
   - Strategy 模式（LLM 决策）
   - State 模式（AgentContext）
4. Agent 接口设计讨论

### 理解检查
- 学生能理解 execute 方法的概念
- 后续思考题（Tool 接口、Memory 设计）未深入讨论

### 下一步计划
- 手写一个极简 Agent（约 100 行）
- 或搭建环境使用 LangChain4j

---

## Day 2 补充（同日下午）- 手写极简 ReAct Agent

### 最终成果
从零手写完整 ReAct Agent 并接入百炼 API 跑通，输入"今天杭州天气"成功走完完整循环

### 设计迭代亮点
- **Tool 接口**: 模板方法模式 `execute() = convert2Input → doExecute → convert2Output(I,O)`，`getToolPrompt()` 工具自描述
- **AgentContext**: 完整历史模型 `List<ThoughtAndActionInfo>`，每轮存储思考+工具调用+结果
- **Agent 循环**: 递归实现，`maxRoundNum=10` 安全阀，`Action:` 空 → 触发总结轮

### 实际运行结果
```
第1轮: LLM → 调用天气查询(杭州, 2026-01-02)
第2轮: LLM 看到工具结果 → Action 为空 → 终止
总结轮: "今天杭州的天气是26摄氏度。"
```

### 识别的知识缺口
- **Prompt 示例污染**: LLM 将参数示例日期当作真实值使用
- **解析脆弱性**: 字符串 split 在格式偏差时容易崩溃，需结构化输出（JSON）
- **Prompt 调优**: 措辞细节对模型行为影响大，需系统学习

### 学生自我总结（准确）
> "最脆弱的地方：对模型响应结果的处理，和调整 prompt"
