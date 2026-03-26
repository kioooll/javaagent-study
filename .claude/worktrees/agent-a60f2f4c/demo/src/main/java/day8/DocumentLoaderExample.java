package day8;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Day 8: 文档加载 + 切块策略对比
 *
 * 本示例演示：
 * 1. 如何加载真实 PDF/TXT 文档
 * 2. 三种切块策略对比：
 *    - 按字符数切分（简单但可能切断语义）
 *    - 按段落切分（保持语义完整性）
 *    - 递归切分（先按段落，段落太大再按字符）
 * 3. 向量存储持久化（JSON 文件）
 */
public class DocumentLoaderExample {

    public static void main(String[] args) throws Exception {

        // ===== Step 1: 准备测试文档 =====
        // 创建一个临时测试文件（实际使用时换成你的 PDF 路径）
        Path testFile = createTestDocument();

        System.out.println("=== 测试文档已创建：" + testFile + " ===\n");

        // ===== Step 2: 加载文档 =====
        // 方法 1: 加载单个文件
        Document document = FileSystemDocumentLoader.loadDocument(testFile);
        System.out.println("【文档加载】");
        System.out.println("  - 文件名：" + testFile.getFileName());
        System.out.println("  - 总字符数：" + document.text().length());
        System.out.println("  - 前 100 字符：" + document.text().substring(0, Math.min(100, document.text().length())));
        System.out.println();

        // ===== Step 3: 对比不同切块策略 =====
        // 注意：DocumentSplitters 需要 langchain4j 0.34.0+

        // 策略 1: 固定字符数切分（最简单，但可能切断语义）
        System.out.println("【策略 1: 固定字符数切分】");
        var fixedSplitter = DocumentSplitters.recursive(
            200,  // 每块最大字符数
            20    // 重叠字符数（保持上下文）
        );
        List<TextSegment> fixedSegments = fixedSplitter.split(document);
        System.out.println("  - 切块数量：" + fixedSegments.size());
        System.out.println("  - 第一块：" + fixedSegments.get(0).text().substring(0, Math.min(80, fixedSegments.get(0).text().length())) + "...");
        System.out.println();

        // 方法 2: 按段落切分（保持语义）
        System.out.println("【策略 2: 按段落切分】");
        var paragraphSplitter = DocumentSplitters.recursive(
            100,  // 每块最大字符数
            50    // 重叠字符数
        );
        List<TextSegment> paragraphSegments = paragraphSplitter.split(document);
        System.out.println("  - 切块数量：" + paragraphSegments.size());
        System.out.println("  - 第一块：" + paragraphSegments.get(0).text().substring(0, Math.min(80, paragraphSegments.get(0).text().length())) + "...");
        System.out.println();

        // ===== Step 4: 选择一种策略进行向量化 =====
        // 实际使用中建议选择「按段落切分」，语义更完整
        System.out.println("【向量化存储】");
        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("text-embedding-v2")
                .build();

        InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build()
                .ingest(paragraphSegments.stream().map(TextSegment::text).map(Document::new).toList());

        System.out.println("  - 已存储 " + paragraphSegments.size() + " 个向量");
        System.out.println();

        // ===== Step 5: 持久化到文件（下次不用重新 embedding）=====
        Path storeFile = Paths.get("embedding-store.json");
        embeddingStore.serializeToFile(storeFile.toAbsolutePath());
        System.out.println("【持久化】");
        System.out.println("  - 已保存到：" + storeFile.toAbsolutePath());
        System.out.println();

        // ===== Step 6: 从文件加载（验证持久化）=====
        InMemoryEmbeddingStore<TextSegment> loadedStore = InMemoryEmbeddingStore.fromFile(storeFile.toAbsolutePath());
        System.out.println("【从文件加载】");
//        System.out.println("  - 加载后向量数：" + loadedStore.size());
        System.out.println();

        // ===== Step 7: 测试检索效果 =====
        System.out.println("【检索测试】");
        String query = "年假有多少天？";
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2)       // 每次最多召回 2 条
                .minScore(0.5)       // 相似度低于 0.5 的不要
                .build();
        var matches = retriever.retrieve(Query.from(query));
        System.out.println("  查询：\"" + query + "\"");
        System.out.println("  匹配结果：");
        for (var match : matches) {
            System.out.println(match.textSegment().text());
        }
    }

    /**
     * 创建测试文档（模拟公司制度手册）
     */
    private static Path createTestDocument() throws Exception {
        Path tempFile = Paths.get("test-company-handbook.txt");
        String content = """
            # 公司员工手册

            ## 第一章：考勤制度

            1. 工作时间为周一至周五 9:00-18:00，午休 1 小时。
            2. 迟到 30 分钟以内扣款 50 元，超过 30 分钟按事假处理。
            3. 每月允许 2 次弹性打卡，需提前在系统申请。

            ## 第二章：假期管理

            1. 年假：入职满 1 年 5 天，满 3 年 10 天，满 5 年 15 天。
            2. 病假：每年 10 天带薪病假，需提供医院证明。
            3. 婚假：符合国家规定的婚假为 3 天。
            4. 产假：女职工产假 98 天，男方陪产假 15 天。

            ## 第三章：报销制度

            1. 差旅费：需在出行后 7 个工作日内提交申请。
            2. 餐饮费：单次招待上限 200 元/人，需提前申请。
            3. 交通费：地铁、公交实报实销，打车需说明原因。

            ## 第四章：绩效考核

            1. 考核周期：每年 12 月进行年度绩效考核。
            2. 考核等级：S/A/B/C 四个等级。
            3. 年终奖：B 级及以上可参与年终奖分配。

            ## 第五章：远程办公

            1. 每周最多申请 2 天远程办公。
            2. 需提前 1 天向直属上级报备。
            3. 远程期间保持在线，及时响应工作消息。
            """;

        java.nio.file.Files.writeString(tempFile, content);
        return tempFile;
    }
}
