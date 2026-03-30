package day28;

import day16.Agent;
import day16.AgentMessage;
import day16.RagAgent;
import day16.ReportAgent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;

/**
 * Day 28: Plan Guardrail 验证
 *
 * 三个测试场景：
 * 1. 正常 plan → 直接执行
 * 2. plan 含自依赱 → PlanValidator 自动修复后执行
 * 3. plan 含引用不存在 id → 触发重试（DataAgent 模拟此场景通过 mock validator）
 *
 * 注意：场景 2/3 依赖 LLM 生成错误 plan，不容易稳定复现。
 * 所以这里直接用单元测试风格验证 PlanValidator，不依赖 LLM。
 */
public class Day28Main {

    public static void main(String[] args) {
        // ===== 第一部分：PlanValidator 单元验证（不调 LLM）=====
        System.out.println("=== Day 28: PlanValidator 单元验证 ===\n");

        java.util.Set<String> agents = java.util.Set.of("RAG Agent", "Data Agent", "Report Agent");
        PlanValidator validator = new PlanValidator(agents);

        // 场景 1：自依赖 → 自动修复
        System.out.println("-- 场景 1：自依赖修复 --");
        List<TaskNode> plan1 = List.of(
                new TaskNode("1", "RAG Agent", List.of("1")),  // 自依赖
                new TaskNode("2", "Report Agent", List.of("1"))
        );
        List<TaskNode> fixed1 = validator.validate(plan1);
        System.out.println("任务1 depends 修复后：" + fixed1.get(0).depends()); // 应为 []
        System.out.println();

        // 场景 2：引用不存在 id → 抛异常
        System.out.println("-- 场景 2：引用不存在 id --");
        List<TaskNode> plan2 = List.of(
                new TaskNode("1", "RAG Agent", List.of()),
                new TaskNode("2", "Report Agent", List.of("99"))  // id=99 不存在
        );
        try {
            validator.validate(plan2);
            System.out.println("[ERROR] 应该抛出异常但未抛出");
        } catch (IllegalArgumentException e) {
            System.out.println("正确抛出异常：" + e.getMessage());
        }
        System.out.println();

        // 场景 3：未知 agent → 抛异常
        System.out.println("-- 场景 3：未知 Agent --");
        List<TaskNode> plan3 = List.of(
                new TaskNode("1", "GhostAgent", List.of())  // 不在白名单
        );
        try {
            validator.validate(plan3);
            System.out.println("[ERROR] 应该抛出异常但未抛出");
        } catch (IllegalArgumentException e) {
            System.out.println("正确抛出异常：" + e.getMessage());
        }
        System.out.println();

        // ===== 第二部分：集成验证（调 LLM）=====
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请设置环境变量 DASHSCOPE_API_KEY（跳过集成验证）");
            return;
        }

        System.out.println("=== Day 28: 集成验证（含 Guardrail 重试）===\n");

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
        orchestrator.registerAgent(new UnreliableDataAgent());
        orchestrator.registerAgent(new ReportAgent(apiKey));

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
