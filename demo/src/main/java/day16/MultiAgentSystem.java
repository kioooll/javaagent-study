package day16;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;
import java.util.Scanner;

/**
 * Day 16: Multi-Agent 系统主程序
 *
 * 演示：
 * 1. 创建 Orchestrator（编排器）
 * 2. 注册多个 SubAgent
 * 3. 处理用户请求，自动分发执行
 *
 * 系统架构：
 *
 *                      用户
 *                       │
 *                       ▼
 *              ┌─────────────────┐
 *              │  Orchestrator   │
 *              │  (编排器)       │
 *              └────────┬────────┘
 *                       │ 分发
 *         ┌─────────────┼─────────────┐
 *         │             │             │
 *         ▼             ▼             ▼
 *   ┌──────────┐  ┌──────────┐  ┌──────────┐
 *   │ RAG Agent│  │ Data Agent│  │Report    │
 *   │          │  │          │  │ Agent    │
 *   │ 知识库   │  │ 数据分析  │  │ 报告撰写  │
 *   └──────────┘  └──────────┘  └──────────┘
 */
public class MultiAgentSystem {

    private final Orchestrator orchestrator;
    private final String apiKey;

    public MultiAgentSystem(String apiKey) {
        this.apiKey = apiKey;
        this.orchestrator = createOrchestrator();
    }

    /**
     * 创建编排器并注册 SubAgent
     */
    private Orchestrator createOrchestrator() {
        System.out.println("=== 初始化 Multi-Agent 系统 ===\n");

        // 创建 Orchestrator
        ChatLanguageModel chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();

        Orchestrator orchestrator = new Orchestrator(chatModel);

        // 准备 RAG 知识库数据（公司制度文档）
        List<Document> knowledgeBase = List.of(
                Document.from("""
                        公司员工手册 - 考勤制度
                        1. 工作时间：周一至周五 9:00-18:00，午休 1 小时
                        2. 迟到：30 分钟内扣 50 元，超过 30 分钟按事假半天
                        3. 早退：未经批准提前下班按事假半天
                        4. 旷工：未请假缺勤按 3 倍日工资扣款
                        5. 全勤奖：当月无迟到早退缺勤，奖励 200 元
                        """),
                Document.from("""
                        公司员工手册 - 假期管理
                        1. 年假：入职满 1 年 5 天，满 3 年 10 天，满 5 年 15 天
                        2. 病假：每年 10 天带薪病假，需提供医院证明
                        3. 事假：无薪，需提前 3 天申请
                        4. 婚假：符合国家规定 3 天
                        5. 产假：女职工 98 天，男方陪产假 15 天
                        6. 丧假：直系亲属 3 天
                        """),
                Document.from("""
                        公司员工手册 - 报销制度
                        1. 差旅费：交通、住宿实报实销，需提前申请
                        2. 餐饮招待：单次人均 200 元以内，需提前申请
                        3. 市内交通：地铁公交实报实销，打车需说明原因
                        4. 报销流程：提交发票→部门审批→财务审核→打款
                        5. 报销时间：费用发生后 7 个工作日内提交
                        """),
                Document.from("""
                        公司员工手册 - 绩效考核
                        1. 考核周期：年度（12 月）+ 季度（每季度末）
                        2. 考核等级：S(卓越)、A(优秀)、B(良好)、C(待改进)
                        3. S 级：不超过 10%，年终奖 3 个月
                        4. A 级：不超过 30%，年终奖 2 个月
                        5. B 级：年终奖 1 个月
                        6. C 级：无年终奖，进入绩效改进计划
                        """)
        );

        // 注册 SubAgent
        orchestrator.registerAgent(new RagAgent(apiKey, knowledgeBase));
        System.out.println("✓ RAG Agent 已注册（知识库查询）");

        orchestrator.registerAgent(new DataAgent(apiKey));
        System.out.println("✓ Data Agent 已注册（数据分析）");

        orchestrator.registerAgent(new ReportAgent(apiKey));
        System.out.println("✓ Report Agent 已注册（报告撰写）");

        System.out.println("\n=== 系统初始化完成 ===\n");
        return orchestrator;
    }

    /**
     * 处理用户请求
     */
    public void processRequest(String request) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("用户：" + request);
        System.out.println("=".repeat(60));

        AgentMessage requestMessage = new AgentMessage(
                "user",
                "Orchestrator",
                AgentMessage.MessageType.USER_REQUEST,
                request
        );

        AgentMessage response = orchestrator.handle(requestMessage);

        System.out.println("\n助手：" + response.getContent());
        System.out.println();
    }

    /**
     * 交互式运行
     */
    public void runInteractive() {
        System.out.println("""

                ╔══════════════════════════════════════════════════════╗
                ║           Multi-Agent 智能助手系统                    ║
                ╠══════════════════════════════════════════════════════╣
                ║  支持的 Agent：                                       ║
                ║  • RAG Agent    - 查询公司制度/政策                   ║
                ║  • Data Agent   - 查询销售数据/分析                   ║
                ║  • Report Agent - 撰写报告/总结                      ║
                ║                                                      ║
                ║  输入"quit"退出                                       ║
                ╚══════════════════════════════════════════════════════╝

                """);

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("您：");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                System.out.println("再见！");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            processRequest(input);
        }

        orchestrator.destroy();
    }

    /**
     * 运行预设测试用例
     */
    public void runTests() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║            Multi-Agent 系统 - 测试用例                ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        // 测试 1: RAG Agent - 知识库查询
        processRequest("公司年假政策是什么？入职 2 年有几天年假？");

        // 测试 2: Data Agent - 数据查询
        processRequest("今年销售情况怎么样？");

        // 测试 3: Data Agent - 产品分析
        processRequest("哪个产品卖得最好？销量如何？");

        // 测试 4: 多 Agent 协作 - 需要 RAG + Report
        processRequest("帮我总结一下公司的绩效考核制度");

        // 测试 5: 多 Agent 协作 - 需要 Data + Report
        processRequest("分析一下销售数据，写个报告");

        // 测试 6: 复杂任务 - 可能需要所有 Agent
        processRequest("今年销售怎么样？最好的产品是什么？再对比一下公司绩效政策");
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("错误：请设置环境变量 DASHSCOPE_API_KEY");
            System.err.println("export DASHSCOPE_API_KEY=your_api_key");
            return;
        }

        MultiAgentSystem system = new MultiAgentSystem(apiKey);

        // 运行测试用例
        system.runTests();

        // 或者进入交互模式
        // system.runInteractive();
    }
}
