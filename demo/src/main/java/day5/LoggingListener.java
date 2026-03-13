package day5;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

public class LoggingListener implements ChatModelListener {

    @Override
    public void onRequest(ChatModelRequestContext context) {
        System.out.println("===== LLM 请求 =====");
        System.out.println(context.request().messages());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        System.out.println("===== LLM 响应 =====");
        System.out.println(context.response().aiMessage());
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        System.out.println("===== LLM 报错 =====");
        System.out.println(context.error().getMessage());
    }
}
