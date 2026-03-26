package day24;

import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import com.alibaba.dashscope.exception.ApiException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Day 24: 带重试 + 熔断的 LLM 客户端
 *
 * 架构：
 *   调用方 → [Retry 装饰器] → [CircuitBreaker 装饰器] → LLM API
 *
 * 重试策略：最多3次，指数退避（1s → 2s → 4s），仅网络/限流异常触发
 * 熔断策略：10次请求中失败率 > 50% 则 OPEN，10s 后进入 HALF-OPEN 探测
 */
public class ResilientLlmClient {

    private final QwenStreamingChatModel streamingModel;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ResilientLlmClient(String apiKey) {
        this.streamingModel = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();

        // ---- 重试配置 ----
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)                                     // 最多3次（含第1次）
                .waitDuration(Duration.ofSeconds(1))                // 初始等待1s
                .intervalFunction(attempt ->                        // 指数退避：1s → 2s → 4s
                        Duration.ofSeconds((long) Math.pow(2, attempt - 1)).toMillis())
                .retryOnException(this::isRetryable)             // 只重试可恢复的异常
                .build();
        this.retry = RetryRegistry.ofDefaults().retry("llm-retry", retryConfig);

        // ---- 熔断器配置 ----
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)                              // 统计最近10次请求
                .failureRateThreshold(50)                          // 失败率 > 50% → OPEN
                .waitDurationInOpenState(Duration.ofSeconds(10))   // OPEN 持续10s
                .permittedNumberOfCallsInHalfOpenState(2)          // HALF-OPEN 放2个探测请求
                .build();
        // Registry 保证全局单例：同名返回同一个实例
        this.circuitBreaker = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker("llm-api", cbConfig);

        // 监听熔断器状态变化（便于观察）
        circuitBreaker.getEventPublisher()
                .onStateTransition(e ->
                        System.out.printf("[CircuitBreaker] 状态变化: %s → %s%n",
                                e.getStateTransition().getFromState(),
                                e.getStateTransition().getToState()));
    }

    /**
     * 带重试 + 熔断的异步流式调用
     */
    public CompletableFuture<String> generateAsync(String prompt, Consumer<String> tokenConsumer) {
        // Resilience4j 用 Supplier/Callable 包装同步操作
        // 但 streaming 是回调风格，我们把"注册回调并等待完成"包装成一个同步操作
        return CompletableFuture.supplyAsync(() ->
                Retry.decorateSupplier(retry,
                        CircuitBreaker.decorateSupplier(circuitBreaker,
                                () -> doGenerate(prompt, tokenConsumer)
                        )
                ).get()  // Supplier.get()，触发执行（含重试+熔断逻辑）
        );
    }

    /**
     * 实际调用 LLM（同步阻塞直到 streaming 完成）
     * 这是被 Retry + CircuitBreaker 包装的"一次尝试"
     */
    private String doGenerate(String prompt, Consumer<String> tokenConsumer) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder fullText = new StringBuilder();

        streamingModel.generate(prompt, new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                fullText.append(token);
                tokenConsumer.accept(token);
            }

            @Override
            public void onComplete(Response response) {
                future.complete(fullText.toString());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        // join() 在这里是合理的：doGenerate 本身会被 supplyAsync 放入线程池
        // 所以阻塞的是线程池线程，不是主线程
        return future.join();
    }

    /**
     * 判断异常是否值得重试
     *
     * 可重试：网络层错误(-1)、限流(429)、服务端临时错误(500/503)
     * 不可重试：认证失败(401)、参数错误(400) → 重试无意义
     */
    private boolean isRetryable(Throwable e) {
        if (e instanceof java.io.IOException) return true;

        if (e instanceof com.alibaba.dashscope.exception.ApiException apiEx) {
            int statusCode = apiEx.getStatus().getStatusCode();
            // 网络错误（SDK 内部设为 -1）
            if (statusCode == -1) return true;
            // 限流
            if (statusCode == 429) return true;
            // 服务端临时错误
            if (statusCode == 500 || statusCode == 503) return true;
            // 401/400 等不重试
            return false;
        }
        return false;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
