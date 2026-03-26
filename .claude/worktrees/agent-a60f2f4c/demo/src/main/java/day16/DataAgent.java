package day16;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Data Agent - 数据分析专家
 *
 * 职责：
 * - 查询业务数据（销售、订单、用户等）
 * - 执行数据分析（统计、趋势、对比）
 * - 返回结构化的数据结果
 *
 * 典型使用场景：
 * - "Q1 销售额是多少？"
 * - "分析一下销售趋势"
 * - "哪个产品卖得最好？"
 *
 * 注意：实际生产环境应该连接真实数据库
 * 这里用模拟数据演示
 */
public class DataAgent implements Agent {

    private final ChatLanguageModel chatModel;
    private final boolean verbose;

    /**
     * 模拟数据库（实际应该连接真实 DB）
     */
    private final Map<String, Object> mockData;

    public DataAgent(String apiKey, boolean verbose) {
        this.verbose = verbose;
        this.chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();

        // 初始化模拟数据
        this.mockData = initMockData();
    }

    public DataAgent(String apiKey) {
        this(apiKey, true);
    }

    private Map<String, Object> initMockData() {
        Map<String, Object> data = new HashMap<>();

        // 销售数据
        data.put("sales_q1", 1500000.0);
        data.put("sales_q2", 1800000.0);
        data.put("sales_q3", 2100000.0);
        data.put("sales_q4", 2500000.0);

        // 产品销量
        data.put("product_A_sales", 5000);
        data.put("product_B_sales", 3500);
        data.put("product_C_sales", 2800);

        // 增长率
        data.put("growth_rate", "15%");

        // 最佳产品
        data.put("best_seller", "产品 A");

        return data;
    }

    @Override
    public String getName() {
        return "Data Agent";
    }

    @Override
    public String getDescription() {
        return "负责数据查询和分析，可以处理销售数据、统计、趋势计算、对比分析等任务。";
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        log("收到数据分析请求：" + message.getContent());

        // Step 1: 分析用户想要什么数据
        String dataRequest = analyzeDataRequest(message.getContent());

        // Step 2: 查询数据
        Map<String, Object> queriedData = queryData(dataRequest);

        if (queriedData.isEmpty()) {
            return message.reply(AgentMessage.MessageType.TASK_RESULT,
                    "抱歉，没有找到相关的数据。");
        }

        log("查询到数据：" + queriedData.keySet());

        // Step 3: 分析数据（用 LLM 生成分析结论）
        String analysis = generateAnalysis(message.getContent(), queriedData);

        // 将原始数据放入元数据
        AgentMessage response = message.reply(AgentMessage.MessageType.TASK_RESULT, analysis);
        response.setMeta("rawData", queriedData);

        return response;
    }

    /**
     * 分析用户想要什么类型的数据
     */
    private String analyzeDataRequest(String request) {
        // 简单版本：基于关键词
        // 生产版本：用 LLM 理解意图

        if (request.contains("销售")) {
            return "sales";
        } else if (request.contains("产品") || request.contains("商品")) {
            return "products";
        } else if (request.contains("趋势") || request.contains("增长")) {
            return "trend";
        } else if (request.contains("分析")) {
            return "general";
        }

        return "general";
    }

    /**
     * 查询数据
     */
    private Map<String, Object> queryData(String category) {
        Map<String, Object> result = new HashMap<>();

        switch (category) {
            case "sales":
                result.put("Q1 销售额", mockData.get("sales_q1"));
                result.put("Q2 销售额", mockData.get("sales_q2"));
                result.put("Q3 销售额", mockData.get("sales_q3"));
                result.put("Q4 销售额", mockData.get("sales_q4"));
                result.put("年增长率", mockData.get("growth_rate"));
                break;

            case "products":
                result.put("产品 A 销量", mockData.get("product_A_sales"));
                result.put("产品 B 销量", mockData.get("product_B_sales"));
                result.put("产品 C 销量", mockData.get("product_C_sales"));
                result.put("最畅销产品", mockData.get("best_seller"));
                break;

            case "trend":
                result.put("Q1", mockData.get("sales_q1"));
                result.put("Q2", mockData.get("sales_q2"));
                result.put("Q3", mockData.get("sales_q3"));
                result.put("Q4", mockData.get("sales_q4"));
                result.put("趋势", "持续增长");
                break;

            default:
                result.put("Q1 销售额", mockData.get("sales_q1"));
                result.put("最畅销产品", mockData.get("best_seller"));
                result.put("增长率", mockData.get("growth_rate"));
        }

        return result;
    }

    /**
     * 用 LLM 生成分析结论
     */
    private String generateAnalysis(String request, Map<String, Object> data) {
        String dataStr = data.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("无数据");

        String prompt = """
你是一个数据分析专家。请根据以下数据回答用户问题。

可用数据：
%s

用户问题：%s

请分析数据并给出有洞察的结论，包括：
1. 关键数字
2. 趋势/变化
3. 简要解读

请简洁回答（200 字以内）：
""".formatted(dataStr, request);

        return chatModel.generate(prompt);
    }

    private void log(String message) {
        if (verbose) {
            System.out.println("[Data Agent] " + message);
        }
    }

    /**
     * 更新数据（用于测试）
     */
    public void updateMockData(Map<String, Object> newData) {
        mockData.putAll(newData);
    }
}
