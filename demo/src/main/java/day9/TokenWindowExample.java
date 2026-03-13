package day9;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.dashscope.QwenTokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * TokenWindowChatMemory 示例
 *
 * 适用场景：
 * - 需要精确控制 token 成本
 * - 防止单条超长消息占满窗口
 * - 模型有上下文窗口限制（如 4096 token）
 */
public class TokenWindowExample {

    public static void main(String[] args) {
        // 使用 LangChain4j 自带的近似 Token 计数器
        TokenWindowChatMemory memory1 = TokenWindowChatMemory.withMaxTokens(1000);

        // 或者使用具体模型的 Tokenizer（更精确）
        QwenTokenizer tokenizer = new QwenTokenizer();
        TokenWindowChatMemory memory2 = TokenWindowChatMemory.builder()
                .maxTokens(1000)
                .tokenizer(tokenizer)
                .build();

        // 添加消息
        memory1.add(dev.langchain4j.data.message.UserMessage.from("你好"));
        memory1.add(dev.langchain4j.data.message.AiMessage.from("你好！有什么可以帮你的？"));

        // 模拟一条超长消息（2000 字符的代码）
        String longCode = generateLongCode(2000);
        memory1.add(dev.langchain4j.data.message.UserMessage.from(longCode));

        System.out.println("=== 当前记忆中的消息 ===");
        for (ChatMessage msg : memory1.messages()) {
            int tokenCount = tokenizer.estimateTokenCountInText(msg.toString());
            System.out.printf("[%s] %d tokens: %s...%n",
                    msg.type().getSimpleName(),
                    tokenCount,
                    msg.toString().substring(0, Math.min(50, msg.toString().length())));
        }

        // 继续添加，观察旧消息被丢弃
        for (int i = 0; i < 5; i++) {
            memory1.add(dev.langchain4j.data.message.UserMessage.from("问题 " + i));
            memory1.add(dev.langchain4j.data.message.AiMessage.from("回答 " + i));
        }

        System.out.println("\n=== 添加 10 条消息后的记忆 ===");
        System.out.println("剩余消息数：" + memory1.messages().size());
        for (ChatMessage msg : memory1.messages()) {
            int tokenCount = tokenizer.estimateTokenCountInText(msg.toString());
            System.out.printf("[%d tokens] %s...%n",
                    tokenCount,
                    msg.toString().substring(0, Math.min(50, msg.toString().length())));
        }
    }

    private static String generateLongCode(int charCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("```java\n");
        sb.append("// 这是一段示例代码\n");
        sb.append("public class Demo {\n");
        while (sb.length() < charCount) {
            sb.append("    System.out.println(\"Hello World\");\n");
        }
        sb.append("}\n");
        sb.append("```");
        return sb.toString();
    }
}
