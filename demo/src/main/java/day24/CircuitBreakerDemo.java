package day24;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示熔断器三态变化：CLOSED → OPEN → HALF-OPEN → CLOSED
 * 用可控的假失败代替真实 LLM 调用，便于观察
 */
public class CircuitBreakerDemo {

    // 控制成功/失败的开关
    static final AtomicInteger callCount = new AtomicInteger(0);
    static boolean forceFailure = true;

    static String fakeLlmCall() throws IOException {
        int n = callCount.incrementAndGet();
        if (forceFailure) {
            throw new IOException("模拟网络超时 (call #" + n + ")");
        }
        return "模拟成功响应 (call #" + n + ")";
    }

    public static void main(String[] args) throws InterruptedException {

        // ---- 配置（故意调小参数便于演示）----
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)                         // 每次最多重试2次，便于观察
                .waitDuration(Duration.ofMillis(100))
                .retryOnException(e -> e instanceof IOException)
                .build();
        Retry retry = RetryRegistry.ofDefaults().retry("demo-retry", retryConfig);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(6)                   // 统计最近6次
                .failureRateThreshold(50)               // 失败率 > 50% → OPEN
                .waitDurationInOpenState(Duration.ofSeconds(3))  // OPEN 等3s
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker("demo-cb", cbConfig);

        // 监听状态变化
        cb.getEventPublisher().onStateTransition(e ->
                System.out.printf("  *** [状态变化] %s → %s ***%n",
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));

        // ---- 阶段1：连续失败，触发 OPEN ----
        System.out.println("=== 阶段1：持续失败，等待熔断 ===");
        for (int i = 1; i <= 8; i++) {
            try {
                String result = Retry.decorateCheckedSupplier(retry,
                        CircuitBreaker.decorateCheckedSupplier(cb,
                                CircuitBreakerDemo::fakeLlmCall))
                        .get();
                System.out.printf("请求%d: 成功 - %s | CB状态: %s%n", i, result, cb.getState());
            } catch (CallNotPermittedException e) {
                System.out.printf("请求%d: 被熔断器拦截（不发请求）| CB状态: %s%n", i, cb.getState());
            } catch (Throwable e) {
                System.out.printf("请求%d: 失败 - %s | CB状态: %s | 失败次数: %d%n",
                        i, e.getMessage(), cb.getState(),
                        cb.getMetrics().getNumberOfFailedCalls());
            }
            Thread.sleep(50);
        }

        // ---- 阶段2：等待 HALF-OPEN ----
        System.out.println("\n=== 阶段2：等待3s，进入 HALF-OPEN ===");
        Thread.sleep(3500);
        System.out.println("当前状态: " + cb.getState());

        // ---- 阶段3：HALF-OPEN 探测，模拟服务恢复 ----
        System.out.println("\n=== 阶段3：服务恢复，探测请求 ===");
        forceFailure = false;  // 模拟服务恢复
        for (int i = 1; i <= 3; i++) {
            try {
                String result = Retry.decorateCheckedSupplier(retry,
                        CircuitBreaker.decorateCheckedSupplier(cb,
                                CircuitBreakerDemo::fakeLlmCall))
                        .get();
                System.out.printf("探测%d: 成功 - %s | CB状态: %s%n", i, result, cb.getState());
            } catch (CallNotPermittedException e) {
                System.out.printf("探测%d: 被拦截 | CB状态: %s%n", i, cb.getState());
            } catch (Throwable e) {
                System.out.printf("探测%d: 失败 | CB状态: %s%n", i, cb.getState());
            }
            Thread.sleep(50);
        }

        System.out.println("\n=== 最终统计 ===");
        System.out.println("实际 LLM 调用次数: " + callCount.get());
        System.out.println("熔断器最终状态: " + cb.getState());
    }
}
