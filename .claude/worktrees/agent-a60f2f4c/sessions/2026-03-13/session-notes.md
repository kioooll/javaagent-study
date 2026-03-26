# 2026-03-13 学习 Session

## Session 概述
- **日期**: 2026-03-13
- **主题**: Function Calling 改造 ReAct Agent（昨天遗留问题收尾）
- **格式**: 问答 + 代码改造

---

## 学习内容

### 问题起点
学生主动提出要解决昨天识别的知识缺口：LLM 输出解析的脆弱性（数组越界）

### 诊断过程
- 学生反馈症状：数组越界
- 引导定位：学生识别出两类问题
  1. `line.indexOf(",")` 返回 -1 → `substring(0, -1)` 越界（空行/格式不对的行）
  2. 非工具行混入 → 解析出错误的 toolName/param
- 修复思路：学生提出"用特定规则包裹工具调用"→ 引出 Function Calling 概念

### Function Calling 理解
**学生初始理解（准确）**：
> "Function Calling 只是把我做的事情标准化了"

- 正确识别：FC 改变的是工具调用协议，不影响 ReAct 递归结构
- 正确识别：FC 后 Thought/Action 不需要解析，Prompt 拼接不需要手动管理

### 改造内容（day1 包原地升级）

**WeatherTool.java**
- 补全 `getToolParamDesc()`，用 Gson JsonObject 构建参数 Schema（city, date）

**AgentContext.java**
- 增加 `List<Message> messages` 字段 + `addMessage()` 方法
- 会话历史从"重建 Prompt 字符串"变为"直接追加 Message"

**Agent.java**
- `callLlm(String)` → `callLlm(List<Message>, List<ToolBase>)` 返回 `Message`
- `agent()` 第一次调用时初始化 USER 消息，后续直接追加
- 移除：`getToolInfo()`, `getThoughtInfo()`, `getPrompt()`, `getSummaryPrompt()`
- 新增：`doSummary()` 用于超出最大轮数时无 tools 调用 LLM 做总结
- 工具调用解析：`response.getToolCalls()` 替代 split 解析

### 边界问题：超出最大轮数
- 学生发现改造后超轮数时 `response.getContent()` 为 null/空
- 指出这是改造引入的 regression（原版有 doSummary）
- 修复：先判断 `exceedMaxRound()` → `doSummary()`，再判断 `toolCalls == null`

---

## 关键洞察

学生总结（准确）：
> "这样改了之后 Thought、Action 都不用解析了，构造 prompt 也不用处理了，看着很简洁"

教学总结：
> 手写 ReAct 让你理解循环本质；Function Calling 是这个循环的标准化实现。先手写再用标准化，顺序正确。

---

## 掌握情况
- **Function Calling 概念**：高，能独立对比两种方案的本质区别
- **边界处理意识**：高，主动发现超轮数时的遗漏
- **代码阅读能力**：高，能快速定位改造前后的差异

## 下一步
- Day 8：RAG 进阶（真实文档加载、切块策略、Re-ranking）或 Spring AI 对比

---

## Day 7 补充（同日）- RAG 基础

### 概念理解
- 学生能准确描述 RAG 的使用场景：输入问句，在知识库中查询相关内容作为模型知识补充
- 主动提到混合搜索（关键词 + 向量结合），认知超出平均水平
- 正确区分关键词搜索（文本相似）vs 向量搜索（语义相似）

### 实战：day7 跑通完整 RAG 链路
四个文档（公司 FAQ），三个问题（含一个知识库没有的问题）

**完整流程掌握**：
- Step1：`Document.from()` 准备文档
- Step2：`QwenEmbeddingModel`（text-embedding-v2）初始化
- Step3：`EmbeddingStoreIngestor` 文档→切块→向量化→存入 `InMemoryEmbeddingStore`
- Step4：`EmbeddingStoreContentRetriever`（maxResults=2, minScore=0.5）
- Step5：`AiServices.contentRetriever()` 挂载检索器

### 深度讨论：切块策略
- 学生正确识别不切块的两个问题：向量失去特征 + 超出 context window
- 理解 overlap 的作用：防止语义在切断点处丢失
- `DocumentSplitters.recursive(300, 30)` — 300字符/块，30字符重叠

### 掌握情况
- **RAG 概念**：高
- **切块策略**：高，能独立分析不切块的问题
- **Overlap 理解**：高，用"断点处上下文"准确描述

---

## Day 6 补充（同日）- LangChain4j 进阶实战

### ChatMemory 验证
- 连续提问"今天杭州天气？" → "那北京呢？"
- 第二问故意不提"天气"和"今天"，模型靠 ChatMemory 正确推断并调用工具
- 结论：ChatMemory 工作正常，`MessageWindowChatMemory` 维护了完整上下文

### Human-in-the-loop 实战
- 实现了控制台 y/n 确认拦截
- 发现抛异常时模型会编造信息，改为 return 指令字符串解决
- 关键结论：给模型的 Tool 返回值要包含明确指令，模型会按指令行事

**架构决策过程**：
1. 尝试装饰器模式 → `@Tool` 注解无法继承，行不通
2. 尝试动态代理 → JDK 只能代理接口，CGLib 代理后注解可能丢失，行不通
3. 最终：`ConfirmService` 注入 Tool，职责分离

**最终实现**：
- `ConfirmService`：独立的确认逻辑，返回 boolean
- `WeatherTool`：注入 `ConfirmService`，拒绝时 return 指令给模型
- `LoggingListener`：实现 `ChatModelListener`，`onRequest/onResponse` 打印完整输入输出
- `QwenChatModel.builder().listeners(List.of(new LoggingListener()))` 注册监听

### 掌握情况
- **ChatMemory 验证**：高，能设计验证场景
- **Human-in-the-loop**：高，经历了完整的方案探索过程
- **框架边界认知**：高，理解了 AiServices 封装越高定制空间越小的权衡

---

## Day 4 补充（同日）- LangChain4j 核心概念

### 学习内容
三个核心抽象概念：`AiServices`、`@Tool`、`ChatMemory`

**AiServices**
- 学生准确映射：`chatLanguageModel` → `callLlm`，`tools` → `registerTool`，`chatMemory` → `List<Message>`
- 理解：AiServices 把 agent 工作流标准化，开发者只关注 Tool 实现和模型选择

**@Tool 注解**
- 学生主动发现局限：`@P` 注解无法表达参数是否必传（缺少 `required` 控制）
- 复杂场景（嵌套结构、enum 约束、动态 Schema）仍需手写 JsonObject

**ChatMemory**
- `MessageWindowChatMemory`（按条数）vs `TokenWindowChatMemory`（按 token）
- 学生理解：day3 的 `List<Message>` 无限增长，生产场景需要裁剪策略

### 深度讨论：Human-in-the-loop
学生主动提出业务干预需求，列出三个真实场景：
1. 工具执行前（高风险操作确认）
2. 模型输出后（敏感词/风控）
3. 强制指定工具（模型判断不准时兜底）

**方案分析**
- 方案 A（拦截逻辑塞进 @Tool）：学生评价"不灵活，不通用" → 本质是职责混乱，缺 AOP 切面
- 方案 B（手写循环）：在 `agent()` 各节点自由插入拦截逻辑，职责清晰

**架构结论**
> AiServices 适合标准场景，复杂干预逻辑适合手写循环，按复杂度选型

### Role 对模型行为的影响（来自 day3 对比观察）
学生观察：day3 不会重复调工具，day1 容易循环
- 根本原因：day1 所有历史塞进 USER 消息，模型无法区分"已执行结果"和"新需求"
- day3 用 TOOL role 明确语义，模型能正确感知对话状态
- 结论：Role 不只是格式，是模型理解对话状态的信号

### 掌握情况
- **LangChain4j 核心抽象**：高，能准确映射到手写实现
- **框架局限性识别**：高，主动质疑 AiServices 对复杂干预的支持
- **架构思维**：高，识别出职责分离问题，类比 AOP 切面
