package day16;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrator - 多 Agent 编排器
 *
 * 核心职责：
 * 1. 意图识别：理解用户想要什么
 * 2. 任务拆解：把复杂任务拆成子任务
 * 3. 分发执行：调用合适的 SubAgent
 * 4. 结果聚合：整合多个结果，生成最终响应
 *
 * 设计模式：
 * - 策略模式：不同的意图识别策略
 * - 责任链：可以链式调用多个 Agent
 */
public class Orchestrator implements Agent {

    /** 注册的 SubAgent 映射表 */
    private final Map<String, Agent> subAgents;

    /** LLM 模型（用于意图识别） */
    private final dev.langchain4j.model.chat.ChatLanguageModel chatModel;

    /** 是否启用详细日志 */
    private final boolean verbose;

    public Orchestrator(dev.langchain4j.model.chat.ChatLanguageModel chatModel, boolean verbose) {
        this.chatModel = chatModel;
        this.verbose = verbose;
        this.subAgents = new ConcurrentHashMap<>();
    }

    public Orchestrator(dev.langchain4j.model.chat.ChatLanguageModel chatModel) {
        this(chatModel, true);
    }

    /**
     * 注册一个 SubAgent
     */
    public void registerAgent(Agent agent) {
        subAgents.put(agent.getName(), agent);
        agent.init();
        log("注册 Agent: " + agent.getName());
    }

    /**
     * 注销一个 SubAgent
     */
    public void unregisterAgent(String name) {
        Agent agent = subAgents.remove(name);
        if (agent != null) {
            agent.destroy();
        }
    }

    /**
     * 获取所有已注册的 Agent 描述
     */
    private String getAllAgentsDescription() {
        return subAgents.values().stream()
                .map(a -> "- " + a.getName() + ": " + a.getDescription())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public String getName() {
        return "Orchestrator";
    }

    @Override
    public String getDescription() {
        return "多 Agent 编排器，负责理解用户意图、拆解任务、分发执行、聚合结果";
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        log("收到消息：" + message);

        // Step 1: 意图识别 - 决定需要调用哪些 Agent
        List<String> targetAgents = identifyIntent(message.getContent());
        log("意图识别结果：需要调用 " + targetAgents);

        if (targetAgents.isEmpty()) {
            // 没有匹配到任何 Agent，直接返回
            return message.reply(AgentMessage.MessageType.FINAL_RESPONSE,
                    "抱歉，我无法理解您的需求。请描述更具体的任务。");
        }

        // Step 2: 分发执行 - 调用各个 SubAgent
        List<AgentMessage> results = new ArrayList<>();
        for (String agentName : targetAgents) {
            Agent agent = subAgents.get(agentName);
            if (agent != null) {
                log("分发给 " + agentName);
                AgentMessage taskMessage = new AgentMessage(
                        getName(),
                        agentName,
                        AgentMessage.MessageType.TASK_DISPATCH,
                        message.getContent()
                );
                taskMessage.setMeta("originalRequest", message.getContent());
                taskMessage.setMeta("requestId", message.getMessageId());

                AgentMessage result = agent.handle(taskMessage);
                results.add(result);
                log(agentName + " 返回结果");
            }
        }

        // Step 3: 结果聚合
        String finalResponse = aggregateResults(message.getContent(), results);

        return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, finalResponse);
    }

    /**
     * 意图识别：决定需要调用哪些 Agent
     *
     * 策略 1：基于规则（简单场景）
     * 策略 2：基于 LLM（复杂场景，推荐）
     */
    private List<String> identifyIntent(String userRequest) {
        if (chatModel == null) {
            // 降级：基于关键词匹配
            return identifyByKeywords(userRequest);
        }

        // 基于 LLM 的意图识别
        String prompt = buildIntentPrompt(userRequest);
        String response = chatModel.generate(prompt);
        return parseIntentResponse(response);
    }

    /**
     * 构建意图识别 Prompt
     */
    private String buildIntentPrompt(String userRequest) {
        return """
你是一个多 Agent 系统的任务分发器。请分析用户请求，判断需要调用哪些 Agent 来处理。

可用的 Agent：
%s

用户请求：%s

请分析用户需要什么能力，从上面选择合适的 Agent（可以有多个）。
需要使用多个agent的时候,要给出依赖关系.depends表示需要依赖的agent执行
单个agent的格式:
    {"id":"1","agent": "Data Agent", "depends": []}
字段解释:
id: 每个调用agent命令的id都要随机3位数字
agent: agent名字
depends: 依赖的agentid

示例：
- "查询公司年假政策" → [{"id": "111", "agent": "RAG Agent", "depends": []}]
- "分析销售数据" → [{"id": "222", "agent": "DATA Agent", "depends": []}]
- "分析一下销售情况并写报告" → [{"id": "222", "agent": "DATA Agent", "depends": []},{"id": "333", "agent": "REPORT Agent", "depends": [222]}]

不要其他内容。
""".formatted(getAllAgentsDescription(), userRequest);
    }

    /**
     * 解析意图识别响应
     */
    private List<String> parseIntentResponse(String response) {
        if (response == null || response.trim().isEmpty() || "NONE".equalsIgnoreCase(response.trim())) {
            return Collections.emptyList();
        }

        return Arrays.stream(response.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(subAgents::containsKey)
                .toList();
    }

    /**
     * 降级方案：基于关键词匹配（当 LLM 不可用时）
     */
    private List<String> identifyByKeywords(String request) {
        List<String> agents = new ArrayList<>();
        String lower = request.toLowerCase();

        // RAG Agent: 知识库、政策、制度、查询
        if (matchesAny(lower, "知识", "政策", "制度", "规定", "流程", "文档", "查询")) {
            agents.add("RAG Agent");
        }

        // Data Agent: 数据、分析、统计、计算
        if (matchesAny(lower, "数据", "分析", "统计", "计算", "趋势", "销售", "报表")) {
            agents.add("Data Agent");
        }

        // Report Agent: 报告、总结、写、生成
        if (matchesAny(lower, "报告", "总结", "写", "生成", "文档", "整理")) {
            agents.add("Report Agent");
        }

        return agents;
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 聚合多个 Agent 的结果
     */
    private String aggregateResults(String originalRequest, List<AgentMessage> results) {
        if (results.isEmpty()) {
            return "抱歉，没有获取到任何结果。";
        }

        if (results.size() == 1) {
            // 只有一个结果，直接返回
            return results.get(0).getContent();
        }

        // 多个结果，需要整合
        // 简单策略：直接拼接
        // 优化策略：用 LLM 总结

        StringBuilder sb = new StringBuilder();
        sb.append("=== 综合以下信息回答您的问题 ===\n\n");

        for (AgentMessage result : results) {
            sb.append("【来自 ").append(result.getSource()).append("】\n");
            sb.append(result.getContent()).append("\n\n");
        }

        // 如果有 LLM，可以让它总结
        if (chatModel != null) {
            String summaryPrompt = """
请根据以下多个来源的信息，整合成一个连贯的回答来响应用户请求。

用户请求：%s

各 Agent 返回结果：
%s

请整合以上信息，生成一个清晰、连贯的回答。如果不同来源有冲突，请指出。
""".formatted(originalRequest, sb.toString());

            try {
                return chatModel.generate(summaryPrompt);
            } catch (Exception e) {
                log("LLM 总结失败，使用原始拼接结果");
                return sb.toString();
            }
        }

        return sb.toString();
    }

    private void log(String message) {
        if (verbose) {
            System.out.println("[Orchestrator] " + message);
        }
    }

    @Override
    public void destroy() {
        subAgents.values().forEach(Agent::destroy);
        subAgents.clear();
    }
}
