package day16;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG Agent - 知识库查询专家
 *
 * 职责：
 * - 加载公司文档到向量数据库
 * - 根据用户问题检索相关文档
 * - 返回基于文档的答案
 *
 * 典型使用场景：
 * - "公司年假政策是什么？"
 * - "怎么申请报销？"
 * - "绩效考核标准是什么？"
 */
public class RagAgent implements Agent {

    private final ContentRetriever retriever;
    private final ChatLanguageModel chatModel;
    private final boolean verbose;

    /**
     * 构造函数
     *
     * @param apiKey 百炼 API Key
     * @param documents 初始文档列表
     * @param verbose 是否启用日志
     */
    public RagAgent(String apiKey, List<Document> documents, boolean verbose) {
        this.verbose = verbose;

        // 初始化 Embedding 模型
        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v2")
                .build();

        // 初始化向量库
        InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        // 索引文档
        if (documents != null && !documents.isEmpty()) {
            EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build()
                    .ingest(documents);
            log("已索引 " + documents.size() + " 个文档");
        }

        // 构建检索器
        this.retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.5)
                .build();

        // 初始化聊天模型
        this.chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();
    }

    public RagAgent(String apiKey, List<Document> documents) {
        this(apiKey, documents, true);
    }

    /**
     * 添加文档到知识库
     */
    public void addDocuments(List<Document> documents) {
        // 注意：InMemoryEmbeddingStore 不支持动态添加，实际生产应该用 PGVector
        // 这里简化处理，重新索引
        log("添加文档功能在当前实现中需要重建索引，建议使用持久化存储");
    }

    @Override
    public String getName() {
        return "RAG Agent";
    }

    @Override
    public String getDescription() {
        return "负责查询知识库，回答基于文档的问题。擅长处理公司政策、制度、流程、员工手册等相关问题。";
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        throw new RuntimeException();
    }

    private void log(String message) {
        if (verbose) {
            System.out.println("[RAG Agent] " + message);
        }
    }
}
