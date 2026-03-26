package day24;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * Day 24: Resilience4j 重试 + 熔断演示
 *
 * 场景1：正常调用（验证基础流程）
 * 场景2：模拟熔断器打开（连续失败触发 OPEN）
 */
public class Day24Main {

    public static void main(String[] args) throws InterruptedException {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // ---- 场景1：正常流式调用 ----
        System.out.println("=== 场景1：正常调用 ===");
        ResilientLlmClient client = new ResilientLlmClient(apiKey);

        client.generateAsync("用一句话介绍Java", token -> System.out.print(token))
                .thenAccept(full -> System.out.printf("%n[完成] 共 %d 字%n", full.length()))
                .exceptionally(e -> {
                    System.out.println("[失败] " + e.getMessage());
                    return null;
                })
                .join();

        // ---- 场景2：观察熔断器状态 ----
        System.out.println("\n=== 场景2：熔断器当前状态 ===");
        System.out.println("状态: " + client.getCircuitBreaker().getState());
        System.out.println("调用统计: " + client.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls() + " 成功 / "
                + client.getCircuitBreaker().getMetrics().getNumberOfFailedCalls() + " 失败");
    }
}
