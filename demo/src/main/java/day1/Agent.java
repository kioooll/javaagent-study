package day1;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Agent {

    private List<Tool> tools = new ArrayList<>();

    public void registerTool(Tool tool) {
        tools.add(tool);
    }

    public void agent(AgentInput input, AgentContext agentContext) throws Exception {
        String llmRes = callLlm(getPrompt(input, agentContext));
        List<ToolInfo> toolInfos = getToolInfo(llmRes);
        ThoughtInfo thoughtInfo = getThoughtInfo(llmRes);
        if (toolInfos.isEmpty() || agentContext.exceedMaxRound()) {
            doSummary(input, agentContext);
            return;
        }
        List<ToolAndResultInfo> res = new ArrayList<>();
        for (ToolInfo info : toolInfos) {
            Tool tool = getTool(info.getToolName());
            String execute = tool.execute(info.getParam());
            res.add(new ToolAndResultInfo(info, execute));
        }
        addToolRes(agentContext, thoughtInfo, res);
        agent(input, agentContext);
    }

    private void addToolRes(AgentContext agentContext,
                            ThoughtInfo thoughtInfo,
                            List<ToolAndResultInfo> res) {
        agentContext.addThoughtAndActionInfo(new ThoughtAndActionInfo(thoughtInfo, res));
        agentContext.addRoundNum();
    }

    private ThoughtInfo getThoughtInfo(String llmRes) {
        if (!llmRes.contains("Thought:")) {
            return new ThoughtInfo("");
        }
        //解析llmRes，获取ThoughtInfo Thought: 之后Action:之前的内容
        String thought = llmRes.split("Thought:")[1]
                .split("Action:")[0].trim();
        return new ThoughtInfo(thought);
    }

    private void doSummary(AgentInput input, AgentContext agentContext) {
        String prompt = getSummaryPrompt(input, agentContext);
        System.out.println("=======总结回答========\n"+ callLlm(prompt));
    }

    public List<ToolInfo> getToolInfo(String llmRes) {
        if (!llmRes.contains("Action:")) {
            return Collections.emptyList();
        }
        //Action:需要执行的工具和对应的参数,以kv键值对的形式给出,如: 查询天气,{"city":"杭州"}多个用换行分割
        //解析llmRes，获取ToolInfo Action: 之后的内容
        String[] split = llmRes.split("Action:");
        if (split.length < 2) {
            return Collections.emptyList();
        }
        String s = split[1];
        String action = s.trim();
        List<ToolInfo> toolInfos = new ArrayList<>();
        for (String line : action.split("\n")) {
            int i = line.indexOf(",");
            String toolName = line.substring(0,i).trim();
            String param = line.substring(i+1).trim();
            toolInfos.add(new ToolInfo(toolName, param));
        }
        return toolInfos;
    }

    public String getPrompt(AgentInput input, AgentContext agentContext) {
        return PromptConstant.PromptTemplate.formatted(
                tools.stream().map(Tool::getToolPrompt).collect(Collectors.joining("\n")),
                input.query,
                agentContext.getThoughtAndActionInfos().stream().map(ThoughtAndActionInfo::getPrompt)
                        .collect(Collectors.joining("\n"))
        );
    }

    public String getSummaryPrompt(AgentInput input, AgentContext agentContext) {
        return PromptConstant.SummaryPromptTemplate.formatted(
                input.query,
                agentContext.getThoughtAndActionInfos().stream().map(ThoughtAndActionInfo::getPrompt)
                        .collect(Collectors.joining("\n"))
        );
    }

    /**
     * 调用大模型
     *
     * @param prompt
     * @return
     */
    public String callLlm(String prompt) {
        System.out.println("=================\nprompt:\n" + prompt);
        Generation generation = new Generation();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        GenerationParam param = GenerationParam.builder()
                .model("qwen-turbo")
                .messages(List.of(userMsg))
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        GenerationResult result = null;
        try {
            result = generation.call(param);
        } catch (NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException(e);
        }
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        System.out.println("=================\n模型结果:\n" + content);
        return content;
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
