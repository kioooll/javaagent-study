package day8;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FAQ 文档处理工具类
 *
 * FAQ 文档特点：
 * - 结构清晰：Q: 问题 \n A: 答案
 * - Q 和 A 有强语义关联，不应分开
 * - 用户问题通常能直接匹配到 Q 部分
 *
 * 切块策略：按 Q&A 对切分，每个问答作为一块
 */
public class FaqUtils {

    /**
     * 将 FAQ 文档按 Q&A 对切分
     *
     * 支持格式：
     * Q: 问题 1
     * A: 答案 1
     *
     * Q: 问题 2
     * A: 答案 2
     *
     * 或
     *
     * 问题 1：...
     * 答案：...
     *
     * @param document FAQ 文档
     * @return 切分后的 TextSegment 列表
     */
    public static List<TextSegment> splitByQAPair(Document document) {
        List<TextSegment> result = new ArrayList<>();

        String text = document.text();

        // 匹配 "Q: xxx \n A: xxx" 格式
        Pattern pattern1 = Pattern.compile(
            "Q:\\s*([^\n]+)\\s*\\n\\s*A:\\s*([^\n]+(?:\\n(?!Q:)[^\n]+)*)",
            Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern1.matcher(text);
        while (matcher.find()) {
            String question = matcher.group(1).trim();
            String answer = matcher.group(2).trim();
            String qaPair = "Q: " + question + "\nA: " + answer;
            result.add(TextSegment.from(qaPair));
        }

        // 如果没有匹配到 Q:A 格式，尝试 "问题：xxx \n 答案：xxx" 格式
        if (result.isEmpty()) {
            Pattern pattern2 = Pattern.compile(
                "([^\n]*问题 [：:][^\n]+)\\s*\\n\\s*([^\n]*答案 [：:][^\n]+(?:\\n(?![^\n]*问题 [：:])[^\n]+)*)",
                Pattern.CASE_INSENSITIVE
            );
            matcher = pattern2.matcher(text);
            while (matcher.find()) {
                String question = matcher.group(1).trim();
                String answer = matcher.group(2).trim();
                result.add(TextSegment.from(question + "\n" + answer));
            }
        }

        // 如果还是没有匹配到，按空行切分（最后的降级方案）
        if (result.isEmpty()) {
            String[] segments = text.split("\\n\\s*\\n");
            for (String segment : segments) {
                String trimmed = segment.trim();
                if (!trimmed.isEmpty()) {
                    result.add(TextSegment.from(trimmed));
                }
            }
        }

        return result;
    }

    /**
     * 将 Q&A 对转成更适合向量化的格式
     *
     * 添加前缀，让向量更好地理解这是一个问答对
     *
     * @param question 问题
     * @param answer 答案
     * @return 格式化后的文本
     */
    public static String formatQAPair(String question, String answer) {
        return "用户可能问：" + question + "\n\n你应该回答：" + answer;
    }

    public static void main(String[] args) {
        // 示例：FAQ 文档
        String faqContent = """
            Q: 产品支持哪些操作系统？
            A: 支持 Windows 10+, macOS 11+, Ubuntu 20.04+

            Q: 产品价格是多少？
            A: 基础版免费，专业版 99 元/月，企业版需联系销售

            Q: 如何联系客服？
            A: 可以通过以下方式联系客服：
            - 电话：400-123-4567
            - 邮箱：support@example.com
            - 工作时间：周一至周五 9:00-18:00
            """;

        Document document = Document.from(faqContent);
        List<TextSegment> segments = splitByQAPair(document);

        System.out.println("=== FAQ 切块结果 ===\n");
        System.out.println("切块数量：" + segments.size());
        System.out.println();

        for (int i = 0; i < segments.size(); i++) {
            System.out.println("【块 " + (i + 1) + "】");
            System.out.println(segments.get(i).text());
            System.out.println("---");
        }
    }
}
