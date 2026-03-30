package day29;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import day16.Agent;
import day16.AgentMessage;
import day28.PlanValidator;
import day28.TaskNode;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Day 29: Plan-and-Solve Orchestrator + OpenTelemetry 追踪
 *
 * 相比 Day 28 的改进：
 * 1. 根 Span 在 Orchestrator.handle() 开始
 * 2. 每个 SubAgent 任务创建 child span
 * 3. CompletableFuture 异步线程通过 Context.taskWrapping(executor) 传播 trace context
 * 4. 子任务失败标 ERROR，根 span 按业务结果（是否有可用叶子）决定最终状态
 */
public class PlanAndSolveOrchestrator implements Agent {

    private final Map<String, Agent> subAgents = new ConcurrentHashMap<>();
    private final ChatLanguageModel chatModel;
    private final Tracer tracer;

    // Context.taskWrapping 让提交到此 executor 的任务自动携带当前 OTel context
    private final ExecutorService executor =
            Context.taskWrapping(Executors.newFixedThreadPool(10));

    public PlanAndSolveOrchestrator(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
        this.tracer = TracingConfig.getTracer();
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

        PlanValidator validator = new PlanValidator(subAgents.keySet());

        for (int attempt = 1; attempt <= 2; attempt++) {
            String response = chatModel.generate(prompt);
            System.out.println("[Orchestrator] 执行计划（第" + attempt + "次）：" + response);
            try {
                List<TaskNode> nodes = parsePlan(response);
                return validator.validate(nodes);
            } catch (IllegalArgumentException e) {
                System.out.println("[Orchestrator] 计划校验失败（第" + attempt + "次）：" + e.getMessage());
                if (attempt == 2) {
                    System.out.println("[Orchestrator] 重试耗尽，降级为全部串行");
                    return fallbackPlan();
                }
            }
        }
        return fallbackPlan();
    }

    private List<TaskNode> parsePlan(String json) {
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

            if (!subAgents.containsKey(agentName)) {
                throw new IllegalArgumentException("未知 Agent '" + agentName + "'");
            }
            nodes.add(new TaskNode(id, agentName, depends));
        }
        return nodes;
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
                CompletableFuture<AgentMessage> future = CompletableFuture.supplyAsync(
                        () -> executeWithSpan(task, userRequest, agent, Collections.emptyList()),
                        executor
                );
                futureMap.put(task.id(), future);
            } else {
                List<CompletableFuture<AgentMessage>> depFutures = task.depends().stream()
                        .map(futureMap::get)
                        .filter(Objects::nonNull)
                        .toList();

                CompletableFuture<AgentMessage> future = CompletableFuture
                        .allOf(depFutures.toArray(new CompletableFuture[0]))
                        .thenApplyAsync(
                                v -> executeWithSpan(task, userRequest, agent, depFutures),
                                executor
                        );
                futureMap.put(task.id(), future);
            }
        }

        CompletableFuture.allOf(futureMap.values().toArray(new CompletableFuture[0])).join();

        Map<String, AgentMessage> results = new HashMap<>();
        futureMap.forEach((id, f) -> {
            try {
                results.put(id, f.join());
            } catch (Exception e) {
                results.put(id, new AgentMessage(
                        getName(), plan.stream().filter(t -> t.id().equals(id))
                        .findFirst().map(TaskNode::agentName).orElse("unknown"),
                        AgentMessage.MessageType.TASK_ERROR,
                        "[任务失败] " + e.getMessage()
                ));
            }
        });
        return results;
    }

    /**
     * 在 child span 内执行单个 subagent 任务。
     * executor 已经被 Context.taskWrapping 包装，所以这里 Span.current() 能正确拿到父 span。
     */
    private AgentMessage executeWithSpan(TaskNode task, String userRequest,
                                         Agent agent,
                                         List<CompletableFuture<AgentMessage>> depFutures) {
        Span span = tracer.spanBuilder("agent.subagent." + task.agentName())
                .setAttribute("agent.name", task.agentName())
                .setAttribute("task.id", task.id())
                .setAttribute("session.depends", String.join(",", task.depends()))
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            String input = userRequest;
            if (!depFutures.isEmpty()) {
                String upstream = depFutures.stream()
                        .map(f -> f.join().getContent())
                        .collect(Collectors.joining("\n\n---\n\n"));
                input = """
                        用户原始请求：%s

                        上游 Agent 已完成的分析结果：
                        %s

                        请基于以上信息完成你的任务。
                        """.formatted(userRequest, upstream);
            }

            System.out.println("[Orchestrator] 执行：" + task.agentName()
                    + " traceId=" + span.getSpanContext().getTraceId());

            AgentMessage msg = new AgentMessage(
                    getName(), task.agentName(),
                    AgentMessage.MessageType.TASK_DISPATCH, input
            );
            AgentMessage result = agent.handle(msg);
            span.addEvent("task_finished");
            return result;

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            return new AgentMessage(
                    getName(), task.agentName(),
                    AgentMessage.MessageType.TASK_ERROR,
                    "[任务失败] " + task.agentName() + "：" + e.getMessage()
            );
        } finally {
            span.end();
        }
    }

    // ==================== 主入口 ====================

    @Override
    public AgentMessage handle(AgentMessage message) {
        String userRequest = message.getContent();

        // 根 Span：整个请求的追踪起点
        Span rootSpan = tracer.spanBuilder("agent.request")
                .setAttribute("session.id", message.getSource())
                .startSpan();

        try (Scope scope = rootSpan.makeCurrent()) {
            System.out.println("\n[Orchestrator] 收到请求：" + userRequest
                    + " traceId=" + rootSpan.getSpanContext().getTraceId());

            List<TaskNode> plan = plan(userRequest);
            if (plan.isEmpty()) {
                rootSpan.setStatus(StatusCode.ERROR, "无法制定执行计划");
                return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, "无法制定执行计划，请重新描述需求。");
            }

            Map<String, AgentMessage> results = solve(userRequest, plan);
            String finalResponse = aggregateResults(userRequest, plan, results, rootSpan);
            return message.reply(AgentMessage.MessageType.FINAL_RESPONSE, finalResponse);

        } finally {
            rootSpan.end();
        }
    }

    private String aggregateResults(String userRequest, List<TaskNode> plan,
                                    Map<String, AgentMessage> results, Span rootSpan) {
        List<Map.Entry<String, AgentMessage>> errors = results.entrySet().stream()
                .filter(e -> e.getValue().getType() == AgentMessage.MessageType.TASK_ERROR)
                .toList();

        String errorSummary = errors.stream()
                .map(e -> e.getValue().getTarget() + ": " + e.getValue().getContent())
                .collect(Collectors.joining("\n"));

        List<String> errorIds = errors.stream().map(Map.Entry::getKey).toList();

        Set<String> depended = plan.stream()
                .flatMap(t -> t.depends().stream())
                .collect(Collectors.toSet());

        List<String> leafIds = plan.stream()
                .map(TaskNode::id)
                .filter(id -> !depended.contains(id) && !errorIds.contains(id))
                .toList();

        if (!errors.isEmpty()) {
            // 有失败：打 degraded 标记，根 span 仍为 OK（业务降级成功）
            rootSpan.setAttribute("degraded", true);
            rootSpan.setAttribute("failed.agents", errorSummary);
            rootSpan.addEvent("fallback_triggered");
        }

        if (leafIds.isEmpty()) {
            rootSpan.setStatus(StatusCode.ERROR, "所有任务均失败");
            return chatModel.generate("""
                    用户请求：%s

                    执行过程中所有任务均失败，失败详情如下：
                    %s

                    请用用户能理解的语言解释发生了什么，不要暴露技术细节。
                    """.formatted(userRequest, errorSummary));
        }

        // 有可用叶子：根 span OK
        rootSpan.setStatus(StatusCode.OK);

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
                """.formatted(userRequest, combined, errorSummary));
    }

    @Override
    public String getName() { return "Orchestrator"; }

    @Override
    public String getDescription() { return "Plan-and-Solve 编排器（含 OTel 追踪）"; }

    @Override
    public void destroy() {
        executor.shutdown();
        subAgents.values().forEach(Agent::destroy);
    }
}
