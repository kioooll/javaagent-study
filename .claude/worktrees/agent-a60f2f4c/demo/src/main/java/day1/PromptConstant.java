package day1;

public class PromptConstant {

    public static final String PromptTemplate = """
            你是一个ai人工助手,以下是你能使用的工具列表
            %s
            输出结果要按照,以下格式输出
            Thought:思考的内容
            Action:需要执行的工具和对应的参数,以kv键值对的形式给出,如: 查询天气,{"city":"杭州"} 工具名和参数之间用逗号分隔,多个工具用换行符分割
            如果已有信息能回答或者没有需要执行的工具,Action:后面返回空
            
            用户原始问句: %s
            
            历史执行结果:
            %s
            
            """;
    public static final String SummaryPromptTemplate = """
            你是一个ai人工助手,你现在要进行总结回答
            用户原始问句: %s
            
            历史执行结果:
            %s
            
            """;
    public static final String historyThoughtTemplate = """
            Thought: %s
            Action: %s
            """;

    public static final String actionResultTemplate = """
            toolName: %s 
            toolParam: %s 
            toolResult: %s
            """;

}
