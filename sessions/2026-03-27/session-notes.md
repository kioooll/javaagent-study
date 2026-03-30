# Session Notes - 2026-03-27

## 概览
- **主题**: Day 25：Context 大对象的内存管理 + 序列化
- **状态**: 进行中
- **形式**: 概念讲解 + 架构设计问答

---

## 学生问题（尽量保留原话）
1. "继续"
2. 回答基线问题：
   - "1.没有回收的方案.2.json. 3. 每次qa,通过Kafka消费进mysql,每个用户的历史对话加载到redis里"
3. 回答理解检查：
   - "1.始终可达,不过就这点内存没啥问题吧.等对话结束自然回收了.  2.mysql完整对话.redis裁剪后的."
4. 进一步提问：
   - "1.map会一直占内存,如果rag信息是有个单独的rag服务提供的.那context里存什么 2.B"
5. 冲突与写路径问题：
   - "1.写入前 2.先改redis"
6. 额外问题：
   - "你是不能一个目录下并行两个Claude么"

---

## 学生当前理解
- 知道对象**始终可达**时 GC 不能回收，这是内存增长的根因 ✅
- 直觉上认为单会话内存不大，倾向于“对话结束后自然回收” ⚠️
- 能正确做出冷热分层判断：
  - MySQL 存完整对话 ✅
  - Redis 存裁剪后的热上下文 ✅
- 能判断默认生产方案应选 **B：最近 N 轮 + 老历史摘要** ✅
- 开始主动追问：如果 RAG 由独立服务提供，AgentContext 应该保留什么信息 ✅

---

## 本次讲解内容

### 1. 内存问题的本质：不是 GC 弱，而是引用没断
重点说明：
- `List<Message>` / `AgentContext.history` 无限 append 会导致对象一直可达
- 异步回调、Map 缓存、SSE/WebSocket 连接、trace/logging 都可能延长对象生命周期
- 生产问题不是单个会话大，而是**大量并发会话同时存活**

### 2. 数据分层设计
讲解了 Agent 数据的冷热分层：
- **热态**：当前推理需要的最小工作集（最近 N 轮、摘要）
- **温态**：摘要、用户画像、关键结构化状态
- **冷态**：完整历史、审计日志、事件流

结合学生方案，明确建议：
- Kafka：事件流 / 审计 / 可回放
- MySQL：完整长期历史
- Redis：裁剪后的热上下文
- JVM：只保留当前请求所需的最小工作集

### 3. 序列化边界
讲解了：
- JSON 比 Java 原生序列化更适合 Agent 状态持久化
- 应序列化的是：message DTO、summary、user profile、tool result 的结构化字段、session metadata
- 不应序列化的是：`ExecutorService`、`CompletableFuture`、Spring Bean、连接、流、Emitter、回调对象

### 4. 默认生产策略
确认学生选对了：
- **最近 N 轮 + 老历史摘要** 是 Java Agent 的默认生产级方案

---

## 关键教学点
- “等对话结束再回收”在 demo 里可能成立，但在生产系统里常常太晚
- 真正要避免的是：把本应在 Redis/MySQL 的数据长期留在 JVM 堆里
- Context 里不应该保存所有 RAG 原文，而应保存**摘要、引用、文档 ID、必要结论**
- 序列化的是**可恢复状态**，不是运行时对象

---

## 学生掌握情况

| 概念 | 置信度 | 备注 |
|------|--------|------|
| 对象可达性导致无法回收 | 高 | 能指出 Map 持有引用会持续占内存 |
| 冷热分层（MySQL / Redis） | 高 | 判断准确 |
| 最近 N 轮 + 摘要 策略 | 高 | 能正确选择默认生产方案 |
| 生产环境内存风险评估 | 中 | 仍有“单会话不大就没问题”的直觉 |
| RAG 独立服务下 Context 边界 | 高 | 能正确区分“引用/摘要”与“原文大对象” |
| 冲突检测时机（写前） | 高 | 能做出正确工程决策 |
| Redis-first 写路径优先级 | 中高 | 方向正确，尚未补全一致性补偿 |
| Summary+Recent 的默认选型意识 | 高 | 明确选择 B |
| 前后矛盾检测机制设计 | 中 | 已识别问题，尚未形成可检测流水线 |
| 一致性补偿设计（Redis/MySQL） | 中 | 需补失败重放与幂等策略 |
| 并行会话容量意识 | 中 | 仍需强化“单会话小 ≠ 系统安全” |
| 轮数触发重摘要策略 | 中高 | 可落地，但需补状态切换/用户纠正触发 |
| Token阈值取舍判断 | 中高 | 明确指出 token 成本难算，倾向轮数策略 |

---

## 当前知识缺口
- **中优先级**：还需要建立“单会话小 ≠ 系统内存安全”的并发容量意识
- **中优先级**：Redis-first 写路径的一致性补偿（Redis成功/MySQL失败时的回放、幂等）
- **中优先级**：前后矛盾可检测化机制（事实槽、冲突标记、summary 对账）
- **低优先级**：token 预算触发器（可先用轮数策略替代）

---

## 下一步
- Day28/29 代码已完成并跑通

## 代码产出

### Day 28（Plan Guardrail）
- `day28/TaskNode.java` — record 节点
- `day28/PlanValidator.java` — 三层校验：自依赖修复 / 引用不存在抛异常 / agent 白名单
- `day28/PlanAndSolveOrchestrator.java` — 含重试逻辑
- `day28/Day28Main.java` — 单元验证 + 集成测试

### Day 29（OpenTelemetry 追踪）
- `day29/TracingConfig.java` — OTel SDK 初始化（LoggingSpanExporter）
- `day29/PlanAndSolveOrchestrator.java` — Context.taskWrapping executor / 根 span / child span / degraded 标记
- `day29/Day29Main.java` — 跑通，观察 traceId 一致性 + 降级 span 标记

## 已验证行为
- 所有子 span traceId 与根 span 相同（context 传播正常）
- UnreliableDataAgent span → ERROR
- 根 span → OK + degraded=true（降级成功）
“前后矛盾”检测流水线（事实槽 + 对账）
  - Redis-first 写路径补偿（异步落库 + 回放 + 幂等）
  - 重摘要触发器组合：每N轮 + 状态切换 + 用户纠正
  - 是否需要引入 token 预算阈值（工程成本权衡）
  - 额外答疑：同目录并行 Claude 的工作方式与注意事项
