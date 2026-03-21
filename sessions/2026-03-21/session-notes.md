# Session Notes - 2026-03-21

## 概览
- **主题**: Day 20 + Day 22：线程池调优 + CompletableFuture 链式调用进阶
- **状态**: 进行中

---

## Day 20：ThreadPoolExecutor 性能调优

### 概念掌握

| 问题 | 学生回答 | 评价 |
|------|----------|------|
| LLM 调用是 CPU 还是 IO 密集型 | IO 密集型 ✅ | 正确 |
| 线程池大小估算 | QPS × 耗时(s) + 30%余量（Little's Law）✅ | 生产上最实用的公式 |
| 线程池选型 | ThreadPoolExecutor 自定义 ✅ | 最合适 |
| 拒绝策略 | 自定义：丢弃 + 记录日志 ✅ | 生产标准做法 |

### ThreadPoolExecutor 核心知识点
- **IO 密集型 core = max**：不依赖队列满才扩张，线程常驻随时可用
- **扩张逻辑**：任务来 → core不够 → 入队 → 队满才扩张到max → 队满+达max → 拒绝策略
- **有界队列**：防止 OOM，容量50
- **自定义拒绝策略**：打印时间戳 + 活跃线程数 + 队列积压

### 运行结果
- 5个并发请求，总提交10个任务（每个2步）
- 活跃峰值5，队列积压始终为0
- 5个并发总耗时12s ≈ 单次耗时，并发无额外开销

---

## Day 22：CompletableFuture 链式调用进阶

### 概念掌握

| 概念 | 学生理解 | 评价 |
|------|----------|------|
| thenCompose vs thenApply | thenCompose返回future，thenApply返回值 ✅ | 准确 |
| anyOf vs allOf | anyOf抢先响应，allOf等全部完成 ✅ | 准确 |
| join() vs get() | join抛unchecked，get抛checked ✅ | 正确 |

### handleAsync() 实现
用 `supplyAsync + thenCompose` 把 Plan 阶段异步化：
```java
CompletableFuture.supplyAsync(() -> plan(userRequest), executor)
    .thenCompose(plan -> {
        if (plan.isEmpty()) return CompletableFuture.failedFuture(...);
        Map results = solve(userRequest, plan);
        return CompletableFuture.completedFuture(message.reply(...));
    });
```

### 关键洞察
- **`completedFuture(handle(message))` 是假异步**：handle() 在参数求值时同步执行，调用方线程照样阻塞
- **solve() 里的 .join() 是瓶颈**：executor 线程阻塞等待，IO 密集型场景浪费
- **彻底异步化**：solveAsync() 返回 CompletableFuture，用 thenApply 替换 join()

### 上下文传递问题
`thenCompose` 里的 `plan` 在下一个 `thenApply` 拿不到，两种解法：
- **方案A（嵌套）**：适合两步以内，简洁
- **方案B（record打包）**：适合三步以上，保持链条扁平，避免回调地狱
- 学生选A，理解了链条变长后的维护成本差异

### Virtual Threads 关联
JDK 21 Virtual Threads 让 .join() 阻塞成本接近零，IO 密集型场景可以不用彻底异步化——这是跳过Day21的代价

## 下一步
- Day 23：Streaming 响应（SSE）
