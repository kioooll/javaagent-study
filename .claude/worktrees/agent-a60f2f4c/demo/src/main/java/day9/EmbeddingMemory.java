package day9;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Embedding 语义记忆 - 长期记忆系统
 *
 * 核心思想：
 * - 所有对话都向量化存入 Embedding Store
 * - 用户提问时，检索语义相关的历史对话
 * - 把检索结果拼入 Prompt，让模型「回忆」起来
 *
 * 适用场景：
 * - 需要记住很久以前的对话
 * - 摘要丢失重要细节
 * - 用户画像、偏好、禁忌等长期信息
 */
public class EmbeddingMemory {

    // 短期记忆
    private final List<ChatMessage> shortTermMemory = new ArrayList<>();

    // 长期记忆（向量存储）
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    // 检索配置
    private final int maxResults = 3;      // 最多检索几条
    private final double minScore = 0.6;   // 最低相似度

    public EmbeddingMemory() {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("text-embedding-v2")
                .build();
    }

    /**
     * 添加消息到记忆系统
     */
    public void add(ChatMessage message) {
        // 短期记忆
        shortTermMemory.add(message);

        // 长期记忆：向量化存储
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String role = message instanceof UserMessage ? "用户" : "助手";
        String content = message.toString();

        // 存储格式：[时间] 角色：内容
        String segmentText = String.format("[%s] %s: %s", timestamp, role, content);
        TextSegment segment = TextSegment.from(segmentText);

        embeddingStore.add(embeddingModel.embed(segment).content(), segment);

        System.out.println("已存储记忆：" + segmentText.substring(0, Math.min(50, segmentText.length())) + "...");
    }

    /**
     * 获取短期记忆（最近 10 条）
     */
    public List<ChatMessage> getShortTermMemory() {
        int start = Math.max(0, shortTermMemory.size() - 10);
        return new ArrayList<>(shortTermMemory.subList(start, shortTermMemory.size()));
    }

    /**
     * 检索长期记忆（语义相关）
     */
    public List<String> retrieveRelevantMemories(String query) {
        var matches = embeddingStore.findRelevant(embeddingModel.embed(query).content(), maxResults, minScore);
        return matches.stream()
                .map(EmbeddingMatch::embedded)
                .map(TextSegment::text)
                .toList();
    }

    /**
     * 发送消息并获取回复
     */
    public String send(String userInput, ChatLanguageModel model) {
        // 1. 用户输入加入记忆
        add(UserMessage.from(userInput));

        // 2. 检索长期记忆
        List<String> relevantMemories = retrieveRelevantMemories(userInput);

        // 3. 构建 Prompt
        StringBuilder prompt = new StringBuilder();
        if (!relevantMemories.isEmpty()) {
            prompt.append("【相关历史记忆】\n");
            for (String memory : relevantMemories) {
                prompt.append("- ").append(memory).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("【当前对话】\n");
        for (ChatMessage msg : getShortTermMemory()) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            prompt.append(role).append(": ").append(msg.toString()).append("\n");
        }

        prompt.append("\n助手：");

        // 4. 调用 LLM
        String response = model.generate(prompt.toString());

        // 5. 助手回复加入记忆
        add(AiMessage.from(response));

        return response;
    }

    public static void main(String[] args) {
        EmbeddingMemory memory = new EmbeddingMemory();

        ChatLanguageModel model = QwenChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-turbo")
                .build();

        System.out.println("=== 开始对话 ===\n");

        // 第 1 轮：告诉模型一个重要信息
        System.out.println("用户：我对花生过敏，记住这个信息");
        String response1 = memory.send("我对花生过敏，记住这个信息", model);
        System.out.println("助手：" + response1 + "\n");

        // 第 2-5 轮：闲聊，稀释记忆
        for (int i = 0; i < 4; i++) {
            String q = "问题" + (i + 1);
            System.out.println("用户：" + q);
            String r = memory.send(q, model);
            System.out.println("助手：" + r + "\n");
        }

        // 第 6 轮：测试长期记忆
        System.out.println("用户：有什么推荐的零食吗？");
        String response6 = memory.send("有什么推荐的零食吗？", model);
        System.out.println("助手：" + response6 + "\n");

        // 第 7 轮：直接问过敏
        System.out.println("用户：我对什么过敏？");
        String response7 = memory.send("我对什么过敏？", model);
        System.out.println("助手：" + response7 + "\n");

        System.out.println("=== 对话结束 ===");
    }
}
