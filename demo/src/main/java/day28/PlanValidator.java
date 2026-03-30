package day28;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Day 28: Plan Guardrail
 *
 * 三层校验：
 * 1. 自依赖检测 → 自动修复（删掉自身 id）
 * 2. 引用不存在 id → 抛 IllegalArgumentException（触发重试）
 * 3. 未知 agent 名称 → 抛 IllegalArgumentException（触发重试）
 */
public class PlanValidator {

    private final Set<String> registeredAgents;

    public PlanValidator(Set<String> registeredAgents) {
        this.registeredAgents = registeredAgents;
    }

    /**
     * 校验并修复执行计划。
     * 可自动修复的问题会修复并继续；无法修复的问题抛 IllegalArgumentException。
     */
    public List<TaskNode> validate(List<TaskNode> plan) {
        Set<String> allIds = plan.stream()
                .map(TaskNode::id)
                .collect(Collectors.toSet());

        List<TaskNode> result = new ArrayList<>();

        for (TaskNode task : plan) {
            // 第三层：agent 白名单
            if (!registeredAgents.contains(task.agentName())) {
                throw new IllegalArgumentException(
                        "未知 Agent '" + task.agentName() + "'，合法值：" + registeredAgents);
            }

            List<String> fixedDepends = new ArrayList<>();
            for (String dep : task.depends()) {
                if (dep.equals(task.id())) {
                    // 第一层：自依赖 → 自动修复，删掉
                    System.out.println("[PlanValidator] 自依赖修复：任务 " + task.id() + " 的 depends 包含自身，已移除");
                    continue;
                }
                if (!allIds.contains(dep)) {
                    // 第二层：引用不存在 id → 触发重试
                    throw new IllegalArgumentException(
                            "任务 " + task.id() + " 的 depends 引用了不存在的 id '" + dep + "'");
                }
                fixedDepends.add(dep);
            }

            result.add(new TaskNode(task.id(), task.agentName(), fixedDepends));
        }

        return result;
    }
}
