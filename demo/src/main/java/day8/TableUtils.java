package day8;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格处理工具类
 *
 * 表格是 RAG 中最难处理的内容之一，因为：
 * 1. 表格有二维结构（行×列），纯文本会丢失结构
 * 2. 单元格的值依赖表头才有意义
 *
 * 常见处理策略：
 * - 转 Markdown（推荐）
 * - 转 JSON
 * - 表头 + 每行单独成块
 */
public class TableUtils {

    /**
     * 将 ASCII 表格转成 Markdown 描述
     *
     * 输入：
     * | 年假 | 1 年 | 3 年 | 5 年 |
     * |------|------|------|------|
     * | 天数 | 5    | 10   | 15   |
     *
     * 输出：
     * 年假：1 年=5 天，3 年=10 天，5 年=15 天
     */
    public static String tableToMarkdown(String[][] table) {
        if (table.length < 2) return "";

        String[] headers = table[0];
        StringBuilder sb = new StringBuilder();

        for (int i = 1; i < table.length; i++) {
            String[] row = table[i];
            // 第一列作为「主题」，后面是键值对
            String topic = row[0];
            sb.append(topic).append("：");

            for (int j = 1; j < headers.length; j++) {
                if (j > 1) sb.append(", ");
                sb.append(headers[j]).append("=").append(row[j]).append("天");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 将表格转成「键值对」列表，适合向量化
     *
     * 输出：
     * ["年假 -1 年=5 天", "年假 -3 年=10 天", "年假 -5 年=15 天"]
     */
    public static List<String> tableToKeyValuePairs(String[][] table) {
        List<String> result = new ArrayList<>();

        if (table.length < 2) return result;

        String[] headers = table[0];

        for (int i = 1; i < table.length; i++) {
            String[] row = table[i];
            String topic = row[0];

            for (int j = 1; j < headers.length; j++) {
                result.add(topic + "-" + headers[j] + "=" + row[j] + "天");
            }
        }

        return result;
    }

    public static void main(String[] args) {
        // 示例：年假政策表格
        String[][] table = {
            {"政策", "1 年", "3 年", "5 年"},
            {"年假", "5", "10", "15"},
            {"病假", "5", "8", "10"},
            {"婚假", "3", "3", "3"}
        };

        System.out.println("=== 原始表格 ===");
        printTable(table);

        System.out.println("\n=== 转 Markdown ===");
        System.out.println(tableToMarkdown(table));

        System.out.println("=== 转键值对（适合向量化）===");
        tableToKeyValuePairs(table).forEach(System.out::println);
    }

    private static void printTable(String[][] table) {
        for (String[] row : table) {
            System.out.print("| ");
            for (String cell : row) {
                System.out.printf("%-6s | ", cell);
            }
            System.out.println();
        }
    }
}
