package day7;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        // ===== Step 1: 准备知识库文档 =====
        List<Document> documents = List.of(
                Document.from("公司年假政策：入职满1年可享受5天年假，满3年10天，满5年15天。年假需提前3天申请。"),
                Document.from("公司报销政策：差旅费需在出行后7个工作日内提交报销申请，超时不予报销。餐饮费单次上限200元。"),
                Document.from("公司远程办公政策：每周最多可申请2天远程办公，需提前1天向直属上级报备。"),
                Document.from("公司绩效考核：每年12月进行年度绩效考核，分为S/A/B/C四个等级，B级及以上可参与年终奖分配。")
        );

        // ===== Step 2: 初始化 Embedding 模型 =====
        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("text-embedding-v2")
                .build();

        // ===== Step 3: 建索引（文档 → 向量 → 存入内存向量库）=====
        InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor.builder().embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore).build().ingest(documents);

        // ===== Step 4: 构建检索器 =====
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2)       // 每次最多召回 2 条
                .minScore(0.5)       // 相似度低于 0.5 的不要
                .build();

        // ===== Step 5: 把检索器挂到 AiServices =====
        ChatLanguageModel model = QwenChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-turbo")
                .build();

        RagAssistant assistant = AiServices.builder(RagAssistant.class)
                .chatLanguageModel(model)
                .contentRetriever(retriever)
                .build();

        // ===== Step 6: 提问 =====
        ask(assistant, "我入职2年了，有几天年假？");
        ask(assistant, "报销餐饮费有什么限制？");
        ask(assistant, "公司有健身房吗？");  // 知识库里没有，看模型怎么回答
    }

    private static void ask(RagAssistant assistant, String question) {
        System.out.println("\n问: " + question);
        System.out.println("答: " + assistant.chat(question));
    }
}
