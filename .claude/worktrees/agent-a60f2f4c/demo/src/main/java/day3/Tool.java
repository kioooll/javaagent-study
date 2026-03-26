package day3;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonObject;

public interface Tool<I, O> {

    String getToolName();

    String getDescription();

    JsonObject getToolParamDesc();

    default ToolBase getToolPrompt() {
        return ToolFunction.builder().function(FunctionDefinition.builder()
                .name(getToolName())
                .description(getDescription())
                .parameters(getToolParamDesc()).build()
        ).build();
    }

    I convert2Input(String actionInput);

    default String execute(String input) {
        I i = convert2Input(input);
        O o = doExecute(i);
        return convert2Output(i, o);
    }

    String convert2Output(I i, O o);

    O doExecute(I i);
}
