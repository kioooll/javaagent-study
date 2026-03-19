package day17;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import day16.Agent;
import day16.AgentMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Day 17: Plan-and-Solve Orchestrator
 *
 * 相比 Day 16 的改进：
 * 1. LLM 先输出带依赖关系的执行计划（Plan 阶段）
 * 2. 按 DAG 拓扑顺序执行，无依赖的任务并行，有依赖的串行（Solve 阶段）
 * 3. 上游结果通过 Orchestrator 收集后传给下游 Agent
 */
public class PlanAndSolveOrchestrator implements Agent {

    /**
     * 执行计划中的单个任务节点
     * 用 record 是因为它是纯数据载体，不需要修改
     */
    record TaskNode(String id, String agentName, List<String> depends) {}

    private final Map<String, Agent> subAgents = new ConcurrentHashMap<>();
    private final ChatLanguageModel chatModel;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public PlanAndSolveOrchestrator(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    public void registerAgent(Agent agent) {
        subAgents.put(agent.getName(), agent);
        agent.init();
        System.out.println("[Orchestrator] 注册 Agent: " + agent.getName());
    }

    // ==================== Plan 阶段 ====================

    /**
     * 让 LLM 输出带依赖关系的执行计划
     */
    private List<TaskNode> plan(String userRequest) {
        String prompt = """
                你是一个多 Agent 系统的任务规划器。请分析用户请求，制定执行计划。

                可用的 Agent：
                %s

                用户请求：%s

                请输出执行计划，格式为 JSON 数组，每个元素包含：
                - id: 任务编号（从"1"开始递增）
                - agent: Agent 名称（必须和上面列表完全一致）
                - depends: 依赖的任务 id 列表（无依赖则为空数组）

                规则：
                - 如果任务 B 需要用到任务 A 的结果，则 B 的 depends 里填 A 的 id
                - 没有依赖关系的任务可以并行执行
                - Report Agent 通常依赖其他 Agent 的结果

                示例：
                "分析销售数据并写报告" →
                [
                  {"id": "1", "agent": "Data Agent", "depends": []},
                  {"id": "2", "agent": "Report Agent", "depends": ["1"]}
                ]

                直接输出 JSON 数组，不要 markdown 代码块，不要其他内容。
                """.formatted(getAllAgentsDescription(), userRequest);

        String response = chatModel.generate(prompt);
        System.out.println("[Orchestrator] 执行计划：" + response);
        return parsePlan(response);
    }

    /**
     * 解析 LLM 返回的 JSON 计划
     */
    private List<TaskNode> parsePlan(String json) {
        try {
            // 清理可能的 markdown 代码块
            String cleaned = json.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```$", "")
                    .trim();

            JSONArray array = JSON.parseArray(cleaned);
            List<TaskNode> nodes = new ArrayList<>();

            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.getString("id");
                String agentName = obj.getString("agent");
                // depends 可能是空数组或字符串数组
                List<String> depends = JSONArray.parseArray(obj.getString("depends"),String.class);
                if (depends == null) depends = Collections.emptyList();

                // 只保留已注册的 Agent
                if (subAgents.containsKey(agentName)) {
                    nodes.add(new TaskNode(id, agentName, depends));
                } else {
                    System.out.println("[Orchestrator] 警告：未知 Agent '" + agentName + "'，跳过");
                }
            }

            return nodes;
        } catch (Exception e) {
            System.out.println("[Orchestrator] 计划解析失败：" + e.getMessage() + "，降级为全部串行");
            // 降级：把所有 Agent 串行执行
            return fallbackPlan();
        }
    }

    /**
     * 降级方案：当 LLM 输出无法解析时，串行执行所有 Agent
     */
    private List<TaskNode> fallbackPlan() {
        List<TaskNode> nodes = new ArrayList<>();
        int i = 1;
        for (String name : subAgents.keySet()) {
            nodes.add(new TaskNode(String.valueOf(i++), name, Collections.emptyList()));
        }
        return nodes;
    }

    private String getAllAgentsDescription() {
        return subAgents.values().stream()
                .map(a -> "- " + a.getName() + ": " + a.getDescription())
                .collect(Collectors.joining("\n"));
    }

    // ==================== Solve 阶段 ====================

    /**
     * 按 DAG 拓扑顺序执行任务
     * 核心逻辑：每个 TaskNode 对应一个 CompletableFuture
     * 无依赖的直接异步执行，有依赖的等上游 Future 完成后再执行
     */
    private Map<String, AgentMessage> solve(String userRequest, List<TaskNode> plan) {
        // key: taskId, value: 该任务对应的 Future
        Map<String, CompletableFuture<AgentMessage>> futureMap = new HashMap<>();

        for (TaskNode task : plan) {
            Agent agent = subAgents.get(task.agentName());

            if (task.depends().isEmpty()) {
                // 无依赖：直接异步执行，输入是原始用户请求
                CompletableFuture<AgentMessage> future = CompletableFuture.supplyAsync(
                        () -> {
                            System.out.println("[Orchestrator] 并行执行：" + task.agentName());
                            AgentMessage msg = new AgentMessage(
                                    getName(), task.agentName(),
                                    AgentMessage.MessageType.TASK_DISPATCH,
                                    userRequest
                            );
                            return agent.handle(msg);
                        },
                        executor
                );
                futureMap.put(task.id(), future);

            } else {
                // 有依赖：等所有上游 Future 完成后再执行
                List<CompletableFuture<AgentMessage>> depFutures = task.depends().stream()
                        .map(futureMap::get)
                        .filter(Objects::nonNull)
                        .toList();

                CompletableFuture<Void> allDeps = CompletableFuture.allOf(
                        depFutures.toArray(new CompletableFuture[0])
                );

                CompletableFuture<AgentMessage> future = allDeps.thenApplyAsync(
                        v -> {
                            // 收集上游结果，拼入下游输入
                            String upstreamResults = depFutures.stream()
                                    .map(f -> f.join().getContent())
                                    .collect(Collectors.joining("\n\n---\n\n"));

                            String enrichedInput = """
                                    用户原始请求：%s

                                    上游 Agent 已完成的分析结果：
                                    %s

                                    请基于以上信息完成你的任务。
                                    """.formatted(userRequest, upstreamResults);

                            System.out.println("[Orchestrator] 串行执行：" + task.agentName() + "（等待依赖完成）");
                            AgentMessage msg = new AgentMessage(
                                    getName(), task.agentName(),
                                    AgentMessage.MessageType.TASK_DISPATCH,
                                    enrichedInput
                            );
                            return agent.handle(msg);
                        },
                        executor
                );
                futureMap.put(task.id(), future);
            }
        }

        // 等待所有任务完成，收集结果
        CompletableFuture.allOf(futureMap.values().toArray(new CompletableFuture[0])).join();

        Map<String, AgentMessage> results = new HashMap<>();
        futureMap.forEach((id, future) -> results.put(id, future.join()));
        return results;
    }

    // ==================== 主入口 ====================

    @Override
    public AgentMessage handle(AgentMessage message) {
        String userRequest = message.getContent();
        System.out.println("\n[Orchestrator] 收到请求：" + userRequest);

        // Plan 阶段
        List<TaskNode> plan = plan(userRequest);
        if (plan.isEmpty()) {
            return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, "无法制定执行计划，请重新描述需求。");
        }
        System.out.println("[Orchestrator] 计划节点数：" + plan.size());

        // Solve 阶段
        Map<String, AgentMessage> results = solve(userRequest, plan);

        // 聚合最终结果
        String finalResponse = aggregateResults(userRequest, plan, results);
        return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, finalResponse);
    }

    /**
     * 聚合结果：取最后一个叶子节点（没有其他任务依赖它）的结果
     * 如果只有一个叶子节点（如 Report Agent），直接返回它的输出即可
     */
    private String aggregateResults(String userRequest, List<TaskNode> plan, Map<String, AgentMessage> results) {
        // 找出所有被依赖的 id
        Set<String> depended = plan.stream()
                .flatMap(t -> t.depends().stream())
                .collect(Collectors.toSet());

        // 叶子节点 = 没有被任何其他任务依赖的任务
        List<String> leafIds = plan.stream()
                .map(TaskNode::id)
                .filter(id -> !depended.contains(id))
                .toList();

        if (leafIds.size() == 1) {
            // 只有一个叶子（最常见情况），直接返回
            return results.get(leafIds.get(0)).getContent();
        }

        // 多个叶子，拼接后让 LLM 总结
        String combined = leafIds.stream()
                .map(id -> results.get(id).getContent())
                .collect(Collectors.joining("\n\n---\n\n"));

        return chatModel.generate("""
                请整合以下信息，生成一个清晰的回答来响应用户请求：%s

                %s
                """.formatted(userRequest, combined));
    }

    @Override
    public String getName() { return "Orchestrator"; }

    @Override
    public String getDescription() { return "Plan-and-Solve 编排器"; }

    @Override
    public void destroy() {
        executor.shutdown();
        subAgents.values().forEach(Agent::destroy);
    }
}
