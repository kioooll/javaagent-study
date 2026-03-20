# Session Notes - 2026-03-20

## 概览
- **主题**: Day 18 + Day 19：Agent 间通信 + DAG 错误传播处理
- **状态**: 完成

---

## Day 18：Agent 间通信 + 调用链追踪防死循环

### 问题引入
场景：Data Agent 分析数据时发现需要查绩效政策，能否直接调用 RAG Agent？

**学生识别的核心风险**：死循环 ✅
- Data Agent → RAG Agent → Data Agent → ...（互相依赖，无终止条件）

### 防死循环两种策略

| 策略 | 缺点 |
|------|------|
| 最大深度限制 | 复杂问题可能合理需要多层调用，会误伤 ✅ |
| 调用链追踪 | 只看"谁调用了谁"不看"为什么"，同一 Agent 不同目的调用两次会误判 ✅ |

**结论**：两者结合，深度阈值按业务配置（3-5层）

### 字段设计决策
**callChain / depth 放正式字段 vs metadata？**
- 学生判断：通用基础设施字段应该是正式字段，metadata 放业务附加数据 ✅
- 对应接口设计原则：通用字段 vs 扩展字段

### 实现
- `AgentMessage` 加 `callChain`（List<String>）和 `depth`（int）正式字段
- `forward(nextAgent, content)`：继承调用链 + 加当前 Agent（非目标）+ depth+1
- **发现 bug**：学生初版 `forward()` 加的是 nextAgent，导致目标 Agent 首次执行就被环检测拦截
- **修复**：改为 `callChain.add(this.target)`，记录"已执行的 Agent"

- `Agent` 接口加 `checkCallChain()` default 方法（深度 + 环检测）
- `safeHandle()` 作为统一拦截入口，子类调用 `safeHandle()` 而非直接 `handle()`

### 运行结果
- 场景1（正常调用链）：Data Agent → RAG Agent 调用成功 ✅
- 场景2（环检测）：触发 RuntimeException，打印调用链 ✅
- 场景3（深度超限）：触发 RuntimeException ✅

---

## Day 19：复杂 DAG 编排 + 错误传播处理

### 问题引入
原 Day17 代码中 `CompletableFuture.allOf().join()` 在某个 SubAgent 抛异常时会崩溃。

**学生初始理解**：会报错影响流程正常执行，没有做异常处理 ✅

### CompletableFuture 异常传播规律（讲解）

| 情况 | 行为 |
|------|------|
| supplyAsync() 里抛异常 | future 变成 failed 状态 |
| thenApplyAsync() 依赖 failed future | 直接跳过，自己也变 failed |
| allOf(...).join() | 任意一个 failed，立即抛 CompletionException |
| 其他并行 future | 不会被取消，继续跑完 |

### 错误策略选型
学生分析三种策略的权衡：
- **Fail-fast**：健壮性不够
- **部分成功**：模型不知道错误信息
- **重试降级**：增加耗时

**最终选择：B+C 融合，不带重试，失败返回 fallback 内容传给下游**
- 下游 Agent 能感知上游失败，写出"诚实报告"
- 优雅降级，不丢信息

### 实现

**TODO 1/2：exceptionally() 捕获异常**
```java
.exceptionally(e -> new AgentMessage(
    getName(), task.agentName(),
    AgentMessage.MessageType.TASK_ERROR,
    "[任务失败] " + task.agentName() + " 执行异常：" +
        (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
))
```
- 学生最初用 `e.getMessage()`，经提示改为 `e.getCause().getMessage()`（CompletionException 包装层）

**f.join() 隐患**（经引导理解）
- 未加 exceptionally 前：`thenApplyAsync` 里 `f.join()` 对 failed future 会重新抛异常
- 加了 exceptionally 后：所有 future 保证正常完成，`f.join()` 永远安全

**TODO 3：aggregateResults() 错误汇总**
- 扫描所有 results 找出 TASK_ERROR 类型
- leafIds 过滤掉失败任务
- `leafIds.isEmpty()` 时让模型根据错误信息生成用户友好解释（学生主动提出）
- 单叶子路径追加 errorAgent 摘要

### 代码 review 发现的问题
| 问题 | 原因 |
|------|------|
| `getSource()` 取到 "Orchestrator" | fallback 构造时 source=getName()，应用 getTarget() |
| 二次 filter TASK_ERROR | 冗余，list 已过滤 |
| 单叶子路径无 errorAgent | 遗漏 |
| TODO 注释残留 | 实现已完成，注释未清理 |

### 运行结果
```
[UnreliableDataAgent] 模拟网络超时...
[Orchestrator] 串行执行：Report Agent（等待依赖完成）
```
Report Agent 报告中如实写明：
> "由于数据库连接异常，本次报告中无法获取完整的2024年度销售数据"

降级流程完整跑通 ✅

### 关键洞察
- 不 fail-fast，不静默吞掉，让模型做诚实的降级输出
- errorAgent 追加时机：依赖链无关的失败需显式追加；叶子节点已感知的失败可不追加（但生产上统一追加摘要更透明）

## 掌握情况

| 概念 | 置信度 |
|------|--------|
| CompletableFuture 异常传播规律 | 高 |
| exceptionally() 降级设计 | 高 |
| 错误策略选型（fail-fast/部分成功/降级） | 高 |
| aggregateResults() 错误汇总 | 中高（有几处 review 问题）|
| CompletionException.getCause() 包装层 | 高（经提醒后理解）|

## 下一步
- Day 20：并发多 Agent 性能调优（Virtual Threads vs 固定线程池）
