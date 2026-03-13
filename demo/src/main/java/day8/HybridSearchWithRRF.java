package day8;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索 + RRF 融合工具类
 *
 * 混合检索 = 关键词检索 (BM25) + 向量检索 (Semantic)
 * RRF(Reciprocal Rank Fusion) = 倒数排名融合算法
 *
 * 核心思想：在多个检索结果中都排前面的文档，应该是真正相关的
 */
public class HybridSearchWithRRF {

    /**
     * 执行混合检索
     *
     * @param query 查询文本
     * @param bm25Results BM25 检索结果（按相关性排序）
     * @param vectorResults 向量检索结果（按相似度排序）
     * @param k RRF 参数，通常设为 60
     * @return 融合后的结果（按 RRF 分数排序）
     */
    public static List<EmbeddingMatch<TextSegment>> hybridSearch(
            String query,
            List<EmbeddingMatch<TextSegment>> bm25Results,
            List<EmbeddingMatch<TextSegment>> vectorResults,
            double k
    ) {
        // RRF 分数映射：文档 ID -> RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();

        // 计算 BM25 的 RRF 分数
        for (int i = 0; i < bm25Results.size(); i++) {
            String id = bm25Results.get(i).embeddingId();
            double score = 1.0 / (k + i + 1);  // 排名从 1 开始，所以 +1
            rrfScores.merge(id, score, Double::sum);
        }

        // 计算向量检索的 RRF 分数
        for (int i = 0; i < vectorResults.size(); i++) {
            String id = vectorResults.get(i).embeddingId();
            double score = 1.0 / (k + i + 1);
            rrfScores.merge(id, score, Double::sum);
        }

        // 合并所有结果
        Map<String, EmbeddingMatch<TextSegment>> allResults = new HashMap<>();
        for (var match : bm25Results) {
            allResults.put(match.embeddingId(), match);
        }
        for (var match : vectorResults) {
            allResults.put(match.embeddingId(), match);
        }

        // 按 RRF 分数排序
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> allResults.get(entry.getKey()))
                .collect(Collectors.toList());
    }

    /**
     * 简易 BM25 实现（用于演示，生产环境建议用 Elasticsearch/Lucene）
     */
    public static class SimpleBM25 {

        private final List<Document> documents;
        private final Map<String, Set<Integer>> invertedIndex = new HashMap<>();
        private final Map<String, Integer> docFreq = new HashMap<>();

        public SimpleBM25(List<Document> documents) {
            this.documents = documents;
            buildIndex();
        }

        private void buildIndex() {
            for (int i = 0; i < documents.size(); i++) {
                String[] tokens = tokenize(documents.get(i).text());
                Set<String> uniqueTokens = new HashSet<>();
                for (String token : tokens) {
                    invertedIndex
                            .computeIfAbsent(token, k -> new HashSet<>())
                            .add(i);
                    uniqueTokens.add(token);
                }
                // 文档频率：包含该词的文档数
                for (String token : uniqueTokens) {
                    docFreq.put(token, docFreq.getOrDefault(token, 0) + 1);
                }
            }
        }

        private String[] tokenize(String text) {
            return text.toLowerCase().split("\\W+");
        }

        public List<EmbeddingMatch<TextSegment>> search(String query, int topK) {
            String[] queryTokens = tokenize(query);
            Map<Integer, Double> scores = new HashMap<>();

            for (String token : queryTokens) {
                Set<Integer> docIds = invertedIndex.get(token);
                if (docIds == null) continue;

                // 简化的 TF-IDF 分数
                int df = docFreq.getOrDefault(token, 1);
                double idf = Math.log((double) documents.size() / df);

                for (int docId : docIds) {
                    scores.merge(docId, idf, Double::sum);
                }
            }

            // 排序取 top K
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                    .limit(topK)
                    .map(entry -> {
                        Document doc = documents.get(entry.getKey());
                        TextSegment segment = TextSegment.from(doc.text());
                        return new EmbeddingMatch<>(
                                entry.getValue(),
                                String.valueOf(entry.getKey()),
                                null,
                                segment
                        );
                    })
                    .collect(Collectors.toList());
        }
    }

    public static void main(String[] args) {
        // 示例：混合检索演示
        List<Document> documents = List.of(
                Document.from("iPhone 15 Pro Max 价格 8999 元起"),
                Document.from("苹果手机最新款 iPhone15ProMax 评测"),
                Document.from("华为 Mate 60 Pro 发售"),
                Document.from("智能手机市场分析报告 2024"),
                Document.from("iPhone 15 系列参数对比")
        );

        String query = "iPhone 15 Pro Max";

        // BM25 检索
        SimpleBM25 bm25 = new SimpleBM25(documents);
        List<EmbeddingMatch<TextSegment>> bm25Results = bm25.search(query, 3);

        System.out.println("=== BM25 结果 ===");
        for (int i = 0; i < bm25Results.size(); i++) {
            System.out.println((i + 1) + ". " + bm25Results.get(i).embedded().text());
        }

        // 模拟向量检索结果（实际应该调用向量数据库）
        List<EmbeddingMatch<TextSegment>> vectorResults = Arrays.asList(
                new EmbeddingMatch<>(0.92, "3", null, TextSegment.from("智能手机市场分析报告 2024")),
                new EmbeddingMatch<>(0.88, "0", null, TextSegment.from("iPhone 15 Pro Max 价格 8999 元起")),
                new EmbeddingMatch<>(0.75, "4", null, TextSegment.from("iPhone 15 系列参数对比"))
        );

        System.out.println("\n=== 向量结果 ===");
        for (int i = 0; i < vectorResults.size(); i++) {
            System.out.println((i + 1) + ". " + vectorResults.get(i).embedded().text());
        }

        // RRF 融合
        List<EmbeddingMatch<TextSegment>> fusedResults = hybridSearch(
                query, bm25Results, vectorResults, 60
        );

        System.out.println("\n=== RRF 融合结果 ===");
        for (int i = 0; i < fusedResults.size(); i++) {
            System.out.println((i + 1) + ". " + fusedResults.get(i).embedded().text());
        }
    }
}
