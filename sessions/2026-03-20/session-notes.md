# Session Notes - 2026-03-20

## 概览
- **主题**: Day 18：Agent 间通信 + 调用链追踪防死循环
- **状态**: 进行中

---

## 涵盖内容

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

### 小问题
- RAG Agent 场景1中回答"知识库无信息"，原因是 forward 传了原始宽泛查询而非精炼词
- 生产建议：forward 前先提炼查询词

## 掌握情况

| 概念 | 置信度 |
|------|--------|
| 死循环风险识别 | 高 |
| 调用链追踪设计 | 高 |
| forward() 正确语义（记录已执行者）| 高（经过 bug 修复加深理解）|
| safeHandle() 拦截模式 | 高 |
| 通用字段 vs 扩展字段设计原则 | 高 |

## 下一步
- Day 19：复杂 DAG 编排 + 错误传播处理
