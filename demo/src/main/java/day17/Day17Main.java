package day17;

import day16.AgentMessage;
import day16.DataAgent;
import day16.RagAgent;
import day16.ReportAgent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;

public class Day17Main {

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

        // 创建 Plan-and-Solve Orchestrator
        PlanAndSolveOrchestrator orchestrator = new PlanAndSolveOrchestrator(model);

        // 注册 SubAgent（复用 day16 的实现）
        List<Document> knowledgeBase = List.of(
                Document.from("""
                        绩效考核制度：S级不超过10%年终3个月；A级不超过30%年终2个月；
                        B级年终1个月；C级无年终奖进入绩效改进计划。
                        """),
                Document.from("""
                        年假制度：入职满1年5天，满3年10天，满5年15天。
                        病假每年10天带薪，需提供医院证明。
                        """)
        );

        orchestrator.registerAgent(new RagAgent(apiKey, knowledgeBase));
        orchestrator.registerAgent(new DataAgent(apiKey));
        orchestrator.registerAgent(new ReportAgent(apiKey));

        System.out.println("\n=== Day 17: Plan-and-Solve Multi-Agent ===\n");

        // 测试 1：单 Agent（无并行）
        run(orchestrator, "公司年假政策是什么？");

        // 测试 2：两个无依赖 Agent 并行
        run(orchestrator, "今年销售怎么样？同时告诉我公司绩效政策。");

        // 测试 3：有依赖链（Data → Report）
        run(orchestrator, "分析一下销售数据，然后写一份简短报告。");

        // 测试 4：三个 Agent，两个并行后汇入一个
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
}
