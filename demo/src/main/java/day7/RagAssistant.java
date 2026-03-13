package day7;

import dev.langchain4j.service.SystemMessage;

public interface RagAssistant {

    @SystemMessage("你是一个公司内部助手，只根据提供的文档内容回答问题，不要编造信息。如果文档中没有相关内容，请直接说不知道。")
    String chat(String question);
}
