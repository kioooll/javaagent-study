package day8;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

/**
 * PGVector 持久化示例
 *
 * 适用场景：
 * - 公司已有 PostgreSQL 数据库
 * - 需要 SQL 查询能力（比如按部门、时间过滤）
 * - 数据量中等（百万级向量）
 *
 * 前置条件：
 * 1. PostgreSQL 9.6+ (推荐 14+)
 * 2. 安装 pgvector 插件：CREATE EXTENSION vector;
 * 3. 添加 Maven 依赖：
 *    <dependency>
 *      <groupId>dev.langchain4j</groupId>
 *      <artifactId>langchain4j-pgvector</artifactId>
 *      <version>0.36.2</version>
 *    </dependency>
 */
public class PgVectorExample {

    public static void main(String[] args) {

        // ===== Step 1: 初始化 Embedding 模型 =====
        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("text-embedding-v2")
                .build();

        // ===== Step 2: 创建 PGVector 存储 =====
        EmbeddingStore<TextSegment> embeddingStore = PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("rag_db")              // 数据库名
                .user("postgres")                // 数据库用户
                .password("your_password")       // 数据库密码
                .table("document_embeddings")    // 表名（自动创建）
                .dimension(1536)                 // 向量维度（根据 embedding 模型）
                .createTable(true)               // 首次运行自动建表
                .dropTableFirst(false)           // 是否清空重建
                .build();

        // ===== Step 3: 添加文档 =====
        TextSegment segment1 = TextSegment.from("公司年假政策：入职满 1 年 5 天年假");
        TextSegment segment2 = TextSegment.from("公司报销政策：差旅费需 7 天内提交");

        embeddingStore.add(embeddingModel.embed(segment1).content(), segment1);
        embeddingStore.add(embeddingModel.embed(segment2).content(), segment2);

        System.out.println("文档已存入 PGVector");

        // ===== Step 4: 检索 =====
        EmbeddingModel queryModel = QwenEmbeddingModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("text-embedding-v2")
                .build();

        var relevant = embeddingStore.findRelevant(queryModel.embed("年假几天").content(), 2);
        System.out.println("\n检索结果：");
        for (var match : relevant) {
            System.out.println("  [分数：" + match.score() + "] " + match.embedded().text());
        }
    }
}
