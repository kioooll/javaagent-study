package day20;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import day16.Agent;
import day16.AgentMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Day 20: Plan-and-Solve Orchestrator（ThreadPoolExecutor 性能调优）
 *
 * 相比 Day 19 的改进：
 * 1. 用 ThreadPoolExecutor 替换 newFixedThreadPool，精确控制线程池行为
 * 2. core = max，适配 IO 密集型 LLM 调用（不依赖队列满才扩张）
 * 3. 有界队列防止 OOM
 * 4. 自定义拒绝策略：记录日志而非静默丢弃
 * 5. printPoolStats() 可观测线程池运行状态
 */
public class PlanAndSolveOrchestrator implements Agent {

    record TaskNode(String id, String agentName, List<String> depends) {}

    private final Map<String, Agent> subAgents = new ConcurrentHashMap<>();
    private final ChatLanguageModel chatModel;

    private static final int POOL_SIZE = 10;
    private static final int QUEUE_CAPACITY = 50;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ExecutorService executor = new ThreadPoolExecutor(
            POOL_SIZE,
            POOL_SIZE,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            // 自定义拒绝策略：记录日志，不静默丢弃，不抛异常崩流程
            (r, pool) -> System.out.printf("[ThreadPool][%s] ⚠️ 任务被拒绝！活跃线程:%d 队列积压:%d 已完成:%d%n",
                    LocalDateTime.now().format(FMT),
                    pool.getActiveCount(),
                    pool.getQueue().size(),
                    pool.getCompletedTaskCount())
    );

    public void printPoolStats() {
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
        System.out.printf("[ThreadPool] 活跃:%d  队列:%d  已完成:%d  总提交:%d%n",
                tpe.getActiveCount(),
                tpe.getQueue().size(),
                tpe.getCompletedTaskCount(),
                tpe.getTaskCount());
    }

    public PlanAndSolveOrchestrator(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    public void registerAgent(Agent agent) {
        subAgents.put(agent.getName(), agent);
        agent.init();
        System.out.println("[Orchestrator] 注册 Agent: " + agent.getName());
    }

    // ==================== Plan 阶段 ====================

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

    private List<TaskNode> parsePlan(String json) {
        try {
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
                List<String> depends = JSONArray.parseArray(obj.getString("depends"), String.class);
                if (depends == null) depends = Collections.emptyList();

                if (subAgents.containsKey(agentName)) {
                    nodes.add(new TaskNode(id, agentName, depends));
                } else {
                    System.out.println("[Orchestrator] 警告：未知 Agent '" + agentName + "'，跳过");
                }
            }
            return nodes;
        } catch (Exception e) {
            System.out.println("[Orchestrator] 计划解析失败：" + e.getMessage() + "，降级为全部串行");
            return fallbackPlan();
        }
    }

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

    private Map<String, AgentMessage> solve(String userRequest, List<TaskNode> plan) {
        Map<String, CompletableFuture<AgentMessage>> futureMap = new HashMap<>();

        for (TaskNode task : plan) {
            Agent agent = subAgents.get(task.agentName());

            if (task.depends().isEmpty()) {
                // 无依赖：直接异步执行
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
                ).exceptionally(e-> new AgentMessage(
                        getName(), task.agentName(),
                        AgentMessage.MessageType.TASK_ERROR,
                        "[任务失败] "+task.agentName()+" 执行异常：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                ));
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
                            // 收集上游结果（可能含 fallback 内容，但 .join() 不会再抛异常）
                            String upstreamResults = depFutures.stream()
                                    .map(f -> f.join().getContent())
                                    .collect(Collectors.joining("\n\n---\n\n"));

                            String enrichedInput = """
                                    用户原始请求：%s

                                    上游 Agent 已完成的分析结果（部分可能包含失败信息）：
                                    %s

                                    请基于以上信息完成你的任务，如有上游失败请在报告中如实说明。
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
                ).exceptionally(e-> new AgentMessage(
                        getName(), task.agentName(),
                        AgentMessage.MessageType.TASK_ERROR,
                        "[任务失败] "+task.agentName()+" 执行异常：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                ))
                // =============================================
                // TODO 2：在这里也加 .exceptionally()，逻辑同 TODO 1
                // =============================================
                ;
                futureMap.put(task.id(), future);
            }
        }

        // 等待所有任务完成（因为每个 future 都有 exceptionally 兜底，这里不会抛异常）
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

        List<TaskNode> plan = plan(userRequest);
        if (plan.isEmpty()) {
            return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, "无法制定执行计划，请重新描述需求。");
        }
        System.out.println("[Orchestrator] 计划节点数：" + plan.size());

        Map<String, AgentMessage> results = solve(userRequest, plan);

        String finalResponse = aggregateResults(userRequest, plan, results);
        return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, finalResponse);
    }

    /**
     * 聚合结果
     */
    private String aggregateResults(String userRequest, List<TaskNode> plan, Map<String, AgentMessage> results) {
        List<Map.Entry<String, AgentMessage>> list = results.entrySet().stream()
                .filter(e -> e.getValue().getType() == AgentMessage.MessageType.TASK_ERROR)
                .toList();
        String errorAgent = list.stream()
                .map(Map.Entry::getValue)
                .map(agentMessage -> agentMessage.getTarget() + ":" + agentMessage.getContent())
                .collect(Collectors.joining("\n\n"));
        List<String> errorId = list.stream().map(Map.Entry::getKey).toList();

        // 找叶子节点
        Set<String> depended = plan.stream()
                .flatMap(t -> t.depends().stream())
                .collect(Collectors.toSet());

        List<String> leafIds = plan.stream()
                .map(TaskNode::id)
                .filter(id -> !depended.contains(id) && !errorId.contains(id))
                .toList();

        if (leafIds.isEmpty()) {
            return chatModel.generate("""
              用户请求：%s

              执行过程中所有任务均失败，失败详情如下：
              %s

              请用用户能理解的语言解释发生了什么，不要暴露技术细节。
              """.formatted(userRequest, errorAgent));
        }

        if (leafIds.size() == 1) {
            return results.get(leafIds.get(0)).getContent();
        }

        String combined = leafIds.stream()
                .map(id -> results.get(id).getContent())
                .collect(Collectors.joining("\n\n---\n\n"));

        return chatModel.generate("""
                请整合以下信息，生成一个清晰的回答来响应用户请求：%s

                %s
                
                [执行摘要] 以下任务执行失败：
                
                %s
                """.formatted(userRequest, combined,errorAgent));
    }

    @Override
    public String getName() { return "Orchestrator"; }

    @Override
    public String getDescription() { return "Plan-and-Solve 编排器（含错误传播）"; }

    @Override
    public void destroy() {
        executor.shutdown();
        subAgents.values().forEach(Agent::destroy);
    }
}
