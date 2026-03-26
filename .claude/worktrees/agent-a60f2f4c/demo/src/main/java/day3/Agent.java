package day3;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Agent {

    private List<Tool> tools = new ArrayList<>();

    public void registerTool(Tool tool) {
        tools.add(tool);
    }

    public void agent(AgentInput input, AgentContext agentContext) throws Exception {
        // 第一次调用时初始化消息历史
        if (agentContext.getMessages().isEmpty()) {
            agentContext.addMessage(Message.builder()
                    .role(Role.USER.getValue())
                    .content(input.query)
                    .build());
        }

        List<ToolBase> toolFunctions = tools.stream()
                .map(Tool::getToolPrompt)
                .collect(Collectors.toList());

        Message response = callLlm(agentContext.getMessages(), toolFunctions);
        agentContext.addMessage(response);

        if (agentContext.exceedMaxRound()) {
            doSummary(agentContext);
            return;
        }

        List<ToolCallBase> toolCalls = response.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            System.out.println("=======最终回答========\n" + response.getContent());
            return;
        }

        for (ToolCallBase toolCall : toolCalls) {
            ToolCallFunction funcCall = (ToolCallFunction) toolCall;
            String funcName = funcCall.getFunction().getName();
            String funcArgs = funcCall.getFunction().getArguments();
            Tool tool = getTool(funcName);
            String result = tool.execute(funcArgs);
            System.out.println("工具调用: " + funcName + " | 参数: " + funcArgs + " | 结果: " + result);
            agentContext.addMessage(Message.builder()
                    .role(Role.TOOL.getValue())
                    .content(result)
                    .toolCallId(funcCall.getId())
                    .build());
        }

        agentContext.addRoundNum();
        agent(input, agentContext);
    }

    private void doSummary(AgentContext agentContext) {
        Message response = callLlm(agentContext.getMessages(), List.of());
        System.out.println("=======最终回答========\n" + response.getContent());
    }

    public Message callLlm(List<Message> messages, List<ToolBase> toolFunctions) {
        System.out.println("=================\nmessages:\n" + messages);
        Generation generation = new Generation();
        GenerationParam param = GenerationParam.builder()
                .model("qwen-turbo")
                .messages(messages)
                .tools(toolFunctions)
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        GenerationResult result;
        try {
            result = generation.call(param);
        } catch (NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException(e);
        }
        Message response = result.getOutput().getChoices().get(0).getMessage();
        System.out.println("=================\n模型结果:\n" + response);
        return response;
    }

    public Tool getTool(String toolName) {
        return tools.stream().filter(tool -> tool.getToolName().equals(toolName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(toolName + "不存在"));
    }

    @AllArgsConstructor
    public static class AgentInput {
        private String query;
    }
}
