package day9;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 带摘要的 ChatMemory
 *
 * 核心思想：
 * - 新对话用正常消息存储
 * - 当消息数超过阈值时，把最早的消息压缩成摘要
 * - 摘要本身也是一条消息（SYSTEM 角色）
 *
 * 相比简单的 Window 策略：
 * ✅ 保留重要信息（不会被丢弃）
 * ✅ 节省 token（摘要比原文短）
 * ❌ 需要额外调用 LLM（有成本）
 */
public class SummarizingChatMemory {

    // 消息队列
    private final LinkedList<ChatMessage> messages = new LinkedList<>();

    // 摘要配置
    private final int maxMessages = 10;           // 超过多少条开始摘要
    private final int messagesToSummarize = 5;    // 每次摘要多少条

    // 用于摘要的 LLM（可以用便宜的小模型）
    private final ChatLanguageModel summarizerModel;

    public SummarizingChatMemory(ChatLanguageModel summarizerModel) {
        this.summarizerModel = summarizerModel;
        // 初始化时添加一个空的 SYSTEM 摘要
        messages.add(SystemMessage.from("对话摘要："));
    }

    /**
     * 添加消息
     */
    public void add(ChatMessage message) {
        messages.add(message);
        maybeSummarize();
    }

    /**
     * 检查是否需要摘要
     */
    private void maybeSummarize() {
        // 不算 SYSTEM 消息，判断是否超过阈值
        long nonSystemCount = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .count();

        if (nonSystemCount > maxMessages) {
            summarizeOldest();
        }
    }

    /**
     * 摘要最早的消息
     */
    private void summarizeOldest() {
        // 找出最早的 N 条非 SYSTEM 消息
        List<ChatMessage> toSummarize = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .limit(messagesToSummarize)
                .toList();

        if (toSummarize.isEmpty()) return;

        // 构建摘要请求
        String summaryPrompt = buildSummaryPrompt(toSummarize);

        // 调用 LLM 生成摘要
        String newSummary = summarizerModel.generate(summaryPrompt);

        // 移除旧的摘要和已摘要的消息
        messages.removeIf(m -> m instanceof SystemMessage);
        toSummarize.forEach(messages::remove);

        // 添加新摘要
        messages.addFirst(SystemMessage.from("对话摘要：" + newSummary));

        System.out.println("=== 已生成新摘要 ===");
        System.out.println("摘要内容：" + newSummary);
    }

    /**
     * 构建摘要 Prompt
     */
    private String buildSummaryPrompt(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请总结以下对话的关键信息，保留：\n");
        sb.append("- 用户的个人信息（姓名、地点、职业等）\n");
        sb.append("- 用户询问过的主要问题\n");
        sb.append("- 重要的上下文信息\n\n");
        sb.append("用简洁的中文，100 字以内。\n\n");
        sb.append("对话内容：\n");

        for (ChatMessage msg : messages) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.toString()).append("\n");
        }

        sb.append("\n摘要：");
        return sb.toString();
    }

    /**
     * 获取完整消息历史（供 LLM 使用）
     */
    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public static void main(String[] args) {
        // 初始化
        ChatLanguageModel model = QwenChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-turbo")
                .build();

        SummarizingChatMemory memory = new SummarizingChatMemory(model);

        // 模拟对话
        System.out.println("=== 开始对话 ===\n");

        memory.add(UserMessage.from("你好，我叫张三，在杭州工作"));
        memory.add(AiMessage.from("你好张三！很高兴认识你。"));

        memory.add(UserMessage.from("今天杭州天气怎么样？"));
        memory.add(AiMessage.from("杭州今天晴，气温 25°C。"));

        memory.add(UserMessage.from("那明天呢？"));
        memory.add(AiMessage.from("明天也是晴天，26°C。"));

        memory.add(UserMessage.from("后天呢？"));
        memory.add(AiMessage.from("后天有雨，记得带伞。"));

        memory.add(UserMessage.from("好的，谢谢"));
        memory.add(AiMessage.from("不客气！"));

        memory.add(UserMessage.from("公司年假政策是什么？"));
        memory.add(AiMessage.from("入职满 1 年 5 天，满 3 年 10 天，满 5 年 15 天。"));

        // 超过阈值，应该触发摘要
        memory.add(UserMessage.from("我入职 2 年了，有几天？"));
        memory.add(AiMessage.from("你有 5 天年假。"));

        System.out.println("\n=== 最终记忆 ===");
        for (ChatMessage msg : memory.getMessages()) {
            String role = msg instanceof SystemMessage ? "[摘要]" :
                          msg instanceof UserMessage ? "[用户]" : "[助手]";
            String content = msg.toString().substring(0, Math.min(50, msg.toString().length()));
            System.out.println(role + ": " + content + "...");
        }
    }
}
