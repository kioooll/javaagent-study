package day20;

import day16.Agent;
import day16.AgentMessage;
import day16.RagAgent;
import day16.ReportAgent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Day20Main {

    public static void main(String[] args) throws InterruptedException {
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
                Document.from("绩效考核制度：S级不超过10%年终3个月；A级不超过30%年终2个月；B级年终1个月；C级无年终奖。")
        );

        // ==================== 测试 1：基本功能验证 ====================
        System.out.println("=== 测试 1：基本功能（ThreadPoolExecutor 替换验证）===");
        PlanAndSolveOrchestrator orchestrator = buildOrchestrator(model, apiKey, knowledgeBase);

        run(orchestrator, "查一下公司绩效政策和今年销售数据，综合写一份年终总结报告。");
        orchestrator.printPoolStats();
        orchestrator.destroy();

        // ==================== 测试 2：并发压测（观察线程池行为）====================
        System.out.println("\n=== 测试 2：5 个并发请求，观察线程池状态 ===");
        PlanAndSolveOrchestrator orchestrator2 = buildOrchestrator(model, apiKey, knowledgeBase);

        int concurrentUsers = 5;
        CountDownLatch latch = new CountDownLatch(concurrentUsers);
        ExecutorService testExecutor = Executors.newFixedThreadPool(concurrentUsers);

        long start = System.currentTimeMillis();
        for (int i = 0; i < concurrentUsers; i++) {
            final int userId = i + 1;
            testExecutor.submit(() -> {
                try {
                    AgentMessage msg = new AgentMessage(
                            "user-" + userId, "Orchestrator",
                            AgentMessage.MessageType.USER_REQUEST,
                            "分析一下今年销售数据。"
                    );
                    AgentMessage response = orchestrator2.handle(msg);
                    System.out.printf("[用户%d] 收到回复（%d字）%n", userId, response.getContent().length());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 每隔 1 秒打印一次线程池状态
        while (latch.getCount() > 0) {
            Thread.sleep(1000);
            orchestrator2.printPoolStats();
        }
        latch.await();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("%n[压测完成] 5 个并发请求总耗时：%d ms%n", elapsed);
        orchestrator2.printPoolStats();

        testExecutor.shutdown();
        orchestrator2.destroy();
    }

    private static PlanAndSolveOrchestrator buildOrchestrator(
            ChatLanguageModel model, String apiKey, List<Document> knowledgeBase) {
        PlanAndSolveOrchestrator orchestrator = new PlanAndSolveOrchestrator(model);
        orchestrator.registerAgent(new RagAgent(apiKey, knowledgeBase));
        orchestrator.registerAgent(new NormalDataAgent());
        orchestrator.registerAgent(new ReportAgent(apiKey));
        return orchestrator;
    }

    static class NormalDataAgent implements Agent {
        @Override
        public AgentMessage handle(AgentMessage message) {
            return message.reply(AgentMessage.MessageType.TASK_RESULT,
                    "2024年销售数据：Q1=120万，Q2=135万，Q3=142万，Q4=158万，全年合计555万，同比增长18%。");
        }
        @Override
        public String getName() { return "Data Agent"; }
        @Override
        public String getDescription() { return "销售数据分析"; }
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
        System.out.printf("耗时：%d ms%n", elapsed);
    }
}
