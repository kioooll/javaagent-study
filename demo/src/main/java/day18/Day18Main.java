package day18;

import dev.langchain4j.data.document.Document;

import java.util.List;

public class Day18Main {

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请设置环境变量 DASHSCOPE_API_KEY");
            return;
        }

        RagAgent ragAgent = new RagAgent(apiKey, List.of(
                Document.from("绩效考核：S级不超过10%年终3个月；A级不超过30%年终2个月；B级年终1个月；C级无年终奖。")
        ));

        DataAgent dataAgent = new DataAgent(apiKey, ragAgent);

        System.out.println("=== 场景 1：正常调用链 Data Agent → RAG Agent ===\n");
        AgentMessage msg1 = new AgentMessage(
                "user", "Data Agent",
                AgentMessage.MessageType.USER_REQUEST,
                "分析一下销售情况，同时对比绩效政策"
        );
        // 模拟 Orchestrator 发起调用，callChain 初始为空
        try {
            AgentMessage result = dataAgent.safeHandle(msg1);
            System.out.println("\n结果：" + result.getContent());
        } catch (RuntimeException e) {
            System.out.println("异常（不应该出现）：" + e.getMessage());
        }

        System.out.println("\n\n=== 场景 2：死循环检测 ===\n");
        // 模拟一条已经经过 RAG Agent 的消息，再次进入 RAG Agent
        AgentMessage msg2 = new AgentMessage(
                "Data Agent", "RAG Agent",
                AgentMessage.MessageType.AGENT_COLLABORATION,
                "查询绩效政策",
                1  // depth=1，表示已经经过一层
        );
        // 手动构造一个已含 RAG Agent 的调用链，模拟循环场景
        msg2.getCallChain().add("Orchestrator");
        msg2.getCallChain().add("RAG Agent");  // RAG Agent 已在链中

        try {
            AgentMessage result = ragAgent.safeHandle(msg2);
            System.out.println("结果（不应该出现）：" + result.getContent());
        } catch (RuntimeException e) {
            System.out.println("环检测触发（符合预期）：" + e.getMessage());
        }

        System.out.println("\n\n=== 场景 3：深度超限检测 ===\n");
        AgentMessage msg3 = new AgentMessage(
                "SomeAgent", "RAG Agent",
                AgentMessage.MessageType.AGENT_COLLABORATION,
                "查询某内容",
                6  // depth=6，超过 MAX_DEPTH=5
        );

        try {
            AgentMessage result = ragAgent.safeHandle(msg3);
            System.out.println("结果（不应该出现）：" + result.getContent());
        } catch (RuntimeException e) {
            System.out.println("深度检测触发（符合预期）：" + e.getMessage());
        }
    }
}
