package day16;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;
import java.util.Map;

/**
 * Report Agent - 报告撰写专家
 *
 * 职责：
 * - 整合多个来源的信息
 * - 撰写结构化报告/总结
 * - 格式化输出
 *
 * 典型使用场景：
 * - "写个销售报告"
 * - "总结一下今天的讨论"
 * - "整理一份会议纪要"
 *
 * 输入：多个 Agent 的分析结果
 * 输出：结构化的报告文档
 */
public class ReportAgent implements Agent {

    private final ChatLanguageModel chatModel;
    private final boolean verbose;

    /**
     * 报告模板
     */
    public enum ReportType {
        SUMMARY("总结"),
        ANALYSIS("分析报告"),
        WEEKLY("周报"),
        MONTHLY("月报"),
        CUSTOM("自定义");

        private final String label;

        ReportType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public ReportAgent(String apiKey, boolean verbose) {
        this.verbose = verbose;
        this.chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();
    }

    public ReportAgent(String apiKey) {
        this(apiKey, true);
    }

    @Override
    public String getName() {
        return "Report Agent";
    }

    @Override
    public String getDescription() {
        return "负责撰写报告、总结、文档整理等任务。可以整合多个来源的信息，生成结构化的报告文档。";
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        log("收到报告生成请求");

        // 从元数据中获取其他 Agent 的结果
        List<String> sourceContents = message.getMeta("sourceContents", null);
        ReportType reportType = message.getMeta("reportType", ReportType.SUMMARY);

        String content;
        if (sourceContents != null && !sourceContents.isEmpty()) {
            // 有来源数据，整合生成报告
            content = generateReportFromSources(message.getContent(), sourceContents, reportType);
        } else {
            // 没有来源数据，直接根据请求生成
            content = generateSimpleReport(message.getContent(), reportType);
        }

        AgentMessage response = message.reply(AgentMessage.MessageType.TASK_RESULT, content);
        response.setMeta("reportType", reportType.getLabel());

        return response;
    }

    /**
     * 从多个来源生成报告
     */
    private String generateReportFromSources(String request, List<String> sources, ReportType type) {
        String sourcesStr = String.join("\n\n===\n\n", sources);

        String prompt = """
你是一个专业的报告撰写助手。请根据以下多个来源的信息，撰写一份%s。

用户请求：%s

来源信息：
%s

请按照以下结构组织报告：
1. 概述（简要说明报告主题）
2. 关键信息（从来源中提取的重要数据/事实）
3. 分析/结论（基于信息的解读）
4. 建议（如有必要）

要求：
- 结构清晰，使用适当的标题
- 数据准确，不编造信息
- 语言简洁专业
- 如果来源之间有冲突，请指出

报告内容：
""".formatted(type.getLabel(), request, sourcesStr);

        log("正在整合 " + sources.size() + " 个来源生成报告...");
        return chatModel.generate(prompt);
    }

    /**
     * 生成简单报告（无来源数据）
     */
    private String generateSimpleReport(String request, ReportType type) {
        String prompt = """
你是一个专业的报告撰写助手。请根据以下请求撰写一份%s。

用户请求：%s

请生成一份结构化的报告，包括：
- 清晰的标题
- 分节组织内容
- 专业的语言

报告内容：
""".formatted(type.getLabel(), request);

        return chatModel.generate(prompt);
    }

    /**
     * 快速总结工具方法
     */
    public static String summarize(String text, ChatLanguageModel model) {
        String prompt = """
请总结以下内容，提取关键信息（100 字以内）：

%s
""".formatted(text);

        return model.generate(prompt);
    }

    private void log(String message) {
        if (verbose) {
            System.out.println("[Report Agent] " + message);
        }
    }
}
