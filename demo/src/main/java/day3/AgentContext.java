package day3;

import com.alibaba.dashscope.common.Message;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentContext {
    public static final int maxRoundNum = 10;
    private int roundNum;
    private List<Message> messages = new ArrayList<>();

    public boolean exceedMaxRound() {
        return roundNum > maxRoundNum;
    }

    public int addRoundNum() {
        return ++roundNum;
    }

    public void addMessage(Message message) {
        messages.add(message);
    }
}
