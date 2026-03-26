package day1;

public interface Tool<I,O> {

    /**
     * 获取工具名称
     * @return
     */
    String getToolName();

    /**
     * 获取工具描述
     * @return
     */
    String getDescription();

    /**
     * 获取tool参数的描述
     * @return
     */
    String getToolParamDesc();

    default String getToolPrompt() {
        return """
                工具名称: %s
                工具描述: %s
                参数信息: %s
                """.formatted(getToolName(),getDescription(),getToolParamDesc());
    }

    /**
     * 把大模型的输入参数转换成tool的参数
     * @param actionInput
     * @return
     */
    I convert2Input(String actionInput);

    /**
     * 工具执行
     * @param input
     * @return
     */
    default String execute(String input) {
        I i = convert2Input(input);
        O o = doExecute(i);
        return convert2Output(i,o);
    }

    String convert2Output(I i,O o);

    O doExecute(I i);

}
