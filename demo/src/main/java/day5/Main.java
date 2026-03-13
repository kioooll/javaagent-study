package day5;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.dashscope.QwenChatModel;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        ChatLanguageModel model = QwenChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-turbo")
                .listeners(List.of(new LoggingListener()))
                .build();

        WeatherAssistant assistant = AiServices.builder(WeatherAssistant.class)
                .chatLanguageModel(model)
                .tools(new WeatherTool(new ConfirmService()))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        System.out.println("用户: 今天杭州天气怎么样？");
        System.out.println("回答: " + assistant.chat("今天杭州天气怎么样？"));

        System.out.println("\n用户: 那北京呢？");
        System.out.println("回答: " + assistant.chat("那北京呢？"));
    }
}
