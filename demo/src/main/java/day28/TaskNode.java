package day28;

import java.util.List;

/**
 * 执行计划中的单个任务节点
 */
public record TaskNode(String id, String agentName, List<String> depends) {}
