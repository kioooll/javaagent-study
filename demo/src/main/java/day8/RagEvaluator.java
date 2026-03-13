package day8;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/**
 * RAG 效果评估工具类
 *
 * 评估指标：
 * 1. 检索准确率 (Context Recall) - 检索到的内容是否相关
 * 2. 回答忠实度 (Faithfulness) - 回答是否基于检索内容，有无编造
 * 3. 回答相关性 (Answer Relevance) - 回答是否解决了用户问题
 *
 * 评估方法：用 LLM 当裁判
 */
public class RagEvaluator {

    private final ChatLanguageModel evaluatorModel;

    public RagEvaluator(String apiKey) {
        this.evaluatorModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-max")  // 用更强的模型做评判
                .build();
    }

    /**
     * 评估检索结果的准确率
     *
     * @param question 用户问题
     * @param contexts 检索到的上下文
     * @return 准确率评分 (0-10) 和理由
     */
    public EvaluationResult evaluateContextRecall(String question, List<TextSegment> contexts) {
        String prompt = buildContextRecallPrompt(question, contexts);
        String response = evaluatorModel.generate(prompt);
        return parseEvaluationResult(response);
    }

    /**
     * 评估回答的忠实度（是否编造信息）
     *
     * @param question 用户问题
     * @param contexts 检索到的上下文
     * @param answer 模型的回答
     * @return 忠实度评分 (0-10) 和理由
     */
    public EvaluationResult evaluateFaithfulness(String question, List<TextSegment> contexts, String answer) {
        String prompt = buildFaithfulnessPrompt(question, contexts, answer);
        String response = evaluatorModel.generate(prompt);
        return parseEvaluationResult(response);
    }

    /**
     * 评估回答的相关性（是否解决问题）
     *
     * @param question 用户问题
     * @param answer 模型的回答
     * @return 相关性评分 (0-10) 和理由
     */
    public EvaluationResult evaluateRelevance(String question, String answer) {
        String prompt = buildRelevancePrompt(question, answer);
        String response = evaluatorModel.generate(prompt);
        return parseEvaluationResult(response);
    }

    /**
     * 完整评估（所有指标）
     */
    public FullEvaluationResult evaluateFull(String question, List<TextSegment> contexts, String answer) {
        EvaluationResult recall = evaluateContextRecall(question, contexts);
        EvaluationResult faithfulness = evaluateFaithfulness(question, contexts, answer);
        EvaluationResult relevance = evaluateRelevance(question, answer);

        return new FullEvaluationResult(recall, faithfulness, relevance);
    }

    // ==================== Prompt 构建 ====================

    private String buildContextRecallPrompt(String question, List<TextSegment> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 RAG 系统评估专家。请评估检索到的上下文与用户问题的相关性。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("检索到的上下文：\n");
        for (int i = 0; i < contexts.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(contexts.get(i).text()).append("\n");
        }
        sb.append("\n请评分（0-10 分）：\n");
        sb.append("- 10 分：所有上下文都高度相关\n");
        sb.append("- 7-9 分：大部分上下文相关\n");
        sb.append("- 4-6 分：部分上下文相关\n");
        sb.append("- 0-3 分：上下文基本无关\n\n");
        sb.append("请以 JSON 格式返回：{\"score\": 数字，\"reason\": \"理由\"}");
        return sb.toString();
    }

    private String buildFaithfulnessPrompt(String question, List<TextSegment> contexts, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 RAG 系统评估专家。请评估模型回答是否忠实于检索到的上下文（有无编造信息）。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("检索到的上下文：\n");
        for (int i = 0; i < contexts.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(contexts.get(i).text()).append("\n");
        }
        sb.append("\n模型回答：").append(answer).append("\n\n");
        sb.append("请评分（0-10 分）：\n");
        sb.append("- 10 分：回答完全基于上下文，无编造\n");
        sb.append("- 7-9 分：回答基本忠实，略有推断\n");
        sb.append("- 4-6 分：部分信息编造\n");
        sb.append("- 0-3 分：回答与上下文矛盾或完全编造\n\n");
        sb.append("请以 JSON 格式返回：{\"score\": 数字，\"reason\": \"理由\"}");
        return sb.toString();
    }

    private String buildRelevancePrompt(String question, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 RAG 系统评估专家。请评估模型回答是否解决了用户的问题。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("模型回答：").append(answer).append("\n\n");
        sb.append("请评分（0-10 分）：\n");
        sb.append("- 10 分：完美回答问题\n");
        sb.append("- 7-9 分：回答基本切题\n");
        sb.append("- 4-6 分：部分切题\n");
        sb.append("- 0-3 分：答非所问\n\n");
        sb.append("请以 JSON 格式返回：{\"score\": 数字，\"reason\": \"理由\"}");
        return sb.toString();
    }

    private EvaluationResult parseEvaluationResult(String response) {
        // 简化的 JSON 解析（生产环境应该用 Jackson 或 Gson）
        try {
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            // 提取 score
            int scoreStart = json.indexOf("\"score\"") + 8;
            int scoreEnd = json.indexOf(",", scoreStart);
            if (scoreEnd == -1) scoreEnd = json.indexOf("}", scoreStart);
            double score = Double.parseDouble(json.substring(scoreStart, scoreEnd).trim());

            // 提取 reason
            int reasonStart = json.indexOf("\"reason\"") + 10;
            int reasonEnd = json.lastIndexOf("\"");
            String reason = json.substring(reasonStart, reasonEnd).trim();

            return new EvaluationResult(score, reason);
        } catch (Exception e) {
            return new EvaluationResult(-1, "解析失败：" + e.getMessage() + "\n原始响应：" + response);
        }
    }

    // ==================== 结果类 ====================

    public static class EvaluationResult {
        public final double score;
        public final String reason;

        public EvaluationResult(double score, String reason) {
            this.score = score;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return String.format("评分：%.1f/10 - %s", score, reason);
        }
    }

    public static class FullEvaluationResult {
        public final EvaluationResult recall;
        public final EvaluationResult faithfulness;
        public final EvaluationResult relevance;

        public FullEvaluationResult(EvaluationResult recall, EvaluationResult faithfulness, EvaluationResult relevance) {
            this.recall = recall;
            this.faithfulness = faithfulness;
            this.relevance = relevance;
        }

        @Override
        public String toString() {
            return String.format(
                "=== RAG 评估结果 ===\n" +
                "检索准确率：%.1f/10 - %s\n" +
                "回答忠实度：%.1f/10 - %s\n" +
                "回答相关性：%.1f/10 - %s\n",
                recall.score, recall.reason,
                faithfulness.score, faithfulness.reason,
                relevance.score, relevance.reason
            );
        }
    }

    public static void main(String[] args) {
        // 示例：评估 RAG 效果
        RagEvaluator evaluator = new RagEvaluator(System.getenv("DASHSCOPE_API_KEY"));

        String question = "入职 2 年有几天年假？";
        List<TextSegment> contexts = List.of(
            TextSegment.from("公司年假政策：入职满 1 年可享受 5 天年假，满 3 年 10 天，满 5 年 15 天。"),
            TextSegment.from("公司报销政策：差旅费需在出行后 7 个工作日内提交。")  // 这条是无关的
        );
        String answer = "入职 2 年可以享受 5 天年假。";

        System.out.println("=== 检索准确率评估 ===");
        EvaluationResult recall = evaluator.evaluateContextRecall(question, contexts);
        System.out.println(recall);

        System.out.println("\n=== 回答忠实度评估 ===");
        EvaluationResult faithfulness = evaluator.evaluateFaithfulness(question, contexts, answer);
        System.out.println(faithfulness);

        System.out.println("\n=== 回答相关性评估 ===");
        EvaluationResult relevance = evaluator.evaluateRelevance(question, answer);
        System.out.println(relevance);
    }
}
