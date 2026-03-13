package day5;

import dev.langchain4j.service.SystemMessage;

public interface WeatherAssistant {

    @SystemMessage("你是一个天气助手，帮助用户查询天气信息。")
    String chat(String userMessage);
}
