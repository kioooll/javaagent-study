package day23;

import day16.Agent;
import day16.AgentMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.output.Response;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Day 23: Streaming Report Agent
 *
 * 相比普通 ReportAgent 的改进：
 * 1. 使用 QwenStreamingChatModel，每个 token 实时回调
 * 2. 通过 tokenConsumer 把 token 推给调用方（可对接 SSE）
 * 3. 用 new CompletableFuture<>() 桥接回调风格 → Future 风格
 */
public class StreamingReportAgent implements Agent {

    private final QwenStreamingChatModel streamingModel;

    /**
     * tokenConsumer：每收到一个 token 就调用一次
     * 调用方可以传入 SSE emitter、WebSocket session、或者 System.out::print
     */
    private final Consumer<String> tokenConsumer;

    public StreamingReportAgent(String apiKey, Consumer<String> tokenConsumer) {
        this.streamingModel = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .build();
        this.tokenConsumer = tokenConsumer;
    }

    /**
     * 异步流式生成，返回 CompletableFuture<String>（完整文本）
     * 同时通过 tokenConsumer 实时推送每个 token
     */
    public CompletableFuture<String> generateAsync(String prompt) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder fullText = new StringBuilder();

        streamingModel.generate(prompt, new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                fullText.append(token);
                tokenConsumer.accept(token);  // 实时推送（模拟 SSE）
            }

            @Override
            public void onComplete(Response response) {
                future.complete(fullText.toString());  // 回调完成 → Future 完成
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);   // 回调失败 → Future 失败
            }
        });

        return future;  // 立即返回，不阻塞
    }

    /**
     * 同步版（供 Orchestrator 的 solve() 调用，内部等待 streaming 完成）
     */
    @Override
    public AgentMessage handle(AgentMessage message) {
        String prompt = """
                请根据以下信息生成一份简洁的分析报告：

                %s
                """.formatted(message.getContent());

        String fullReport = generateAsync(prompt).join();  // 等流式完成，拿完整文本
        return message.reply(AgentMessage.MessageType.TASK_RESULT, fullReport);
    }

    @Override
    public String getName() { return "Report Agent"; }

    @Override
    public String getDescription() { return "流式报告生成 Agent"; }
}
