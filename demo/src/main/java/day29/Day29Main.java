package day29;

import day16.Agent;
import day16.AgentMessage;
import day16.RagAgent;
import day16.ReportAgent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;

/**
 * Day 29: OpenTelemetry 追踪 Agent 推理链
 *
 * 运行后控制台会打印每个 span 的 traceId/spanId，形如：
 * 'agent.subagent.RAG Agent' : ENDED
 *   traceId=<hex>
 *   spanId=<hex>
 *   attributes={agent.name=RAG Agent, task.id=1, ...}
 *
 * 关键观察点：
 * 1. 所有 subagent span 的 traceId 与根 span 相同（context 传播成功）
 * 2. 失败的 subagent span status=ERROR，根 span status=OK（降级成功标记）
 * 3. 根 span 的 attributes 包含 degraded=true 和 failed.agents
 */
public class Day29Main {

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请设置环境变量 DASHSCOPE_API_KEY");
            return;
        }

        ChatLanguageModel model = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();

        List<Document> knowledgeBase = List.of(
                Document.from("""
                        绩效考核制度：S级不超过10%年终3个月；A级不超过30%年终2个月；
                        B级年终1个月；C级无年终奖进入绩效改进计划。
                        """)
        );

        PlanAndSolveOrchestrator orchestrator = new PlanAndSolveOrchestrator(model);
        orchestrator.registerAgent(new RagAgent(apiKey, knowledgeBase));
        orchestrator.registerAgent(new UnreliableDataAgent());  // 故意失败
        orchestrator.registerAgent(new ReportAgent(apiKey));

        System.out.println("\n=== Day 29: OTel 追踪 Agent 推理链 ===\n");
        System.out.println("观察每个 span 的 traceId，确认并行子任务与根 span 共享同一 traceId。\n");

        run(orchestrator, "查一下公司绩效政策和今年销售数据，综合写一份年终总结报告。");

        orchestrator.destroy();
    }

    private static void run(PlanAndSolveOrchestrator orchestrator, String request) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("用户：" + request);
        System.out.println("=".repeat(60));

        AgentMessage msg = new AgentMessage(
                "user-session-001", "Orchestrator",
                AgentMessage.MessageType.USER_REQUEST,
                request
        );

        long start = System.currentTimeMillis();
        AgentMessage response = orchestrator.handle(msg);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n助手：" + response.getContent());
        System.out.printf("耗时：%d ms%n%n", elapsed);
    }

    /**
     * 模拟必定失败的 DataAgent，用于验证：
     * - 该 span 标 ERROR
     * - 根 span 仍标 OK（降级成功）
     * - rootSpan.attributes 包含 degraded=true
     */
    static class UnreliableDataAgent implements Agent {

        @Override
        public AgentMessage handle(AgentMessage message) {
            System.out.println("[UnreliableDataAgent] 模拟网络超时...");
            throw new RuntimeException("连接数据库超时：jdbc:mysql://data-warehouse:3306");
        }

        @Override
        public String getName() { return "Data Agent"; }

        @Override
        public String getDescription() { return "销售数据分析（模拟故障版）"; }
    }
}
