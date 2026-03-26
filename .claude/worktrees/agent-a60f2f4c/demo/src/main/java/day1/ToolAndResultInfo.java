package day1;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ToolAndResultInfo {

    private ToolInfo toolInfo;

    private String result;

    public String getPrompt() {
        return PromptConstant.actionResultTemplate.formatted(toolInfo.getToolName(), toolInfo.getParam(), result);
    }

}
