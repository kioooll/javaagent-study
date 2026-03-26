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

## Day 23：Streaming 响应（SSE）

### 概念掌握
- 学生已了解流式输出（ChatGPT 打字效果）
- 理解 SSE 是 LLM → Java后端 → 前端的传输机制
- 前端需要按 event type 区分不同阶段（planning/agent_status/content）

### 关键知识点

**回调→Future 桥接（Promise化）**
```java
CompletableFuture<String> future = new CompletableFuture<>();
streamingModel.generate(prompt, new StreamingResponseHandler<>() {
    public void onNext(String token) { tokenConsumer.accept(token); }
    public void onComplete(Response r) { future.complete(fullText); }
    public void onError(Throwable e) { future.completeExceptionally(e); }
});
return future;
```

**new CompletableFuture<>() vs supplyAsync()**
- supplyAsync：框架跑任务、自动完成
- new CompletableFuture<>()：手动遥控器，完成时机在回调里

**tokenConsumer 设计**
- `Consumer<String>` 解耦推送方式
- 可换成 SSE emitter、WebSocket、System.out::print

**handle() 内部 .join()**
- Orchestrator 视角同步，用户视角流式
- 两种风格共存

### 运行结果
- 场景1：token 实时打印，流式效果可见 ✅
- 场景2：handle() 集成 Agent 架构，完整内容通过 reply() 返回 ✅

### 遗留问题（Day24 涉及）
- 用户断开连接时 generateAsync() 仍在跑，需要取消机制

## Day 24：Resilience4j 重试 + 熔断

### 概念掌握

| 问题 | 学生回答 | 评价 |
|------|----------|------|
| 429 时立刻还是等一会重试 | 等一会（指数退避）✅ | 正确 |
| 彻底宕机时一直重试的问题 | 加重服务端压力，应熔断+HALF-OPEN探测 ✅ | 完全正确 |
| Retry/CB 应加在哪层 | callLlm()（DRY原则，通用逻辑）✅ | 正确 |
| 装饰顺序反转会如何 | 漏计重试次数 ✅（+CB无法在中途踩刹车）| 方向对，补充了更重要的点 |
| isRetryable 字符串匹配的问题 | 应按接口文档用 status_code 判断 ✅ | 完全正确 |

### 关键知识点

**熔断器三态**
- CLOSED → OPEN：slidingWindow内失败率超阈值
- OPEN → HALF-OPEN：等待 waitDurationInOpenState 后自动切换
- HALF-OPEN → CLOSED：探测请求成功
- HALF-OPEN → OPEN：探测请求失败

**Registry 全局单例**
- `CircuitBreakerRegistry.circuitBreaker("同名")` 返回同一实例
- 避免每次 new 导致熔断器状态无法积累

**装饰顺序：Retry(外) → CB(内)**
- CB在内层：每次重试都被CB计数，CB可在中途OPEN阻断后续重试
- CB在外层：CB只看到1次逻辑调用结果，无法在重试中途介入

**isRetryable 精确化**
- 用 `ApiException.getStatus().getStatusCode()` 而非字符串匹配
- -1(SDK网络错误) / 429(限流) / 500/503(服务端) → 可重试
- 401/400 → 不可重试

### 运行结果（CircuitBreakerDemo）
- 阶段1：请求3触发OPEN，请求4-8被拦截（5次无意义调用被阻断）
- 阶段2：等待3s → HALF-OPEN
- 阶段3：2次探测成功 → CLOSED
- 总实际调用：9次（vs 11次逻辑请求，省了5次）

## 下一步
- Day 25：Context 大对象的内存管理 + 序列化
