package day23;

import dev.langchain4j.model.dashscope.QwenStreamingChatModel;

/**
 * Day 23: Streaming 响应演示
 *
 * 场景1：直接调用 generateAsync()，观察 token 实时打印
 * 场景2：通过 handle() 集成进 Agent 架构，验证流式+Future桥接
 */
public class Day23Main {

    public static void main(String[] args) throws InterruptedException {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请设置环境变量 DASHSCOPE_API_KEY");
            return;
        }

        // ==================== 场景1：直接流式输出 ====================
        System.out.println("=== 场景1：流式输出（模拟 SSE 推送）===\n");

        // tokenConsumer 传入 System.out::print，每个 token 实时打印
        StreamingReportAgent agent = new StreamingReportAgent(apiKey, token -> {
            System.out.print(token);
            System.out.flush();  // 确保实时显示，不被缓冲
        });

        long start = System.currentTimeMillis();

        agent.generateAsync("""
                请分析以下销售数据并给出建议：
                2024年销售数据：Q1=120万，Q2=135万，Q3=142万，Q4=158万，全年合计555万，同比增长18%。
                """)
            .thenAccept(fullText -> {
                long elapsed = System.currentTimeMillis() - start;
                System.out.printf("%n%n[完成] 共 %d 字，耗时 %d ms%n", fullText.length(), elapsed);
            })
            .exceptionally(ex -> {
                System.err.println("[错误] " + ex.getMessage());
                return null;
            })
            .join();

        // ==================== 场景2：集成进 Agent 架构 ====================
        System.out.println("\n=== 场景2：通过 handle() 集成 Agent 架构 ===\n");

        day16.AgentMessage msg = new day16.AgentMessage(
                "Orchestrator", "Report Agent",
                day16.AgentMessage.MessageType.TASK_DISPATCH,
                "2024年销售数据：Q1=120万，Q2=135万，Q3=142万，Q4=158万，同比增长18%。"
        );

        // handle() 内部调 generateAsync().join()，对 Orchestrator 来说是同步的
        // 但 token 会通过 tokenConsumer 实时打印
        StreamingReportAgent agent2 = new StreamingReportAgent(apiKey, token -> {
            System.out.print(token);
            System.out.flush();
        });

        day16.AgentMessage response = agent2.handle(msg);
        System.out.printf("%n%n[handle() 返回] 完整内容长度：%d 字%n", response.getContent().length());
    }
}
