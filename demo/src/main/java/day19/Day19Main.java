package day19;

import day16.Agent;
import day16.AgentMessage;
import day16.RagAgent;
import day16.ReportAgent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;

public class Day19Main {

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
        // 故意注册一个会报错的 DataAgent，模拟真实故障
        orchestrator.registerAgent(new UnreliableDataAgent());
        orchestrator.registerAgent(new ReportAgent(apiKey));

        System.out.println("\n=== Day 19: DAG 错误传播处理 ===\n");

        // 测试场景：DataAgent 失败，但 RagAgent 成功，Report Agent 应该写出"部分数据缺失"的诚实报告
        run(orchestrator, "查一下公司绩效政策和今年销售数据，综合写一份年终总结报告。");

        orchestrator.destroy();
    }

    private static void run(PlanAndSolveOrchestrator orchestrator, String request) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("用户：" + request);
        System.out.println("=".repeat(60));

        AgentMessage msg = new AgentMessage(
                "user", "Orchestrator",
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
     * 模拟故障的 DataAgent：每次调用必定抛出异常
     * 用于验证 exceptionally() 能正确降级
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
