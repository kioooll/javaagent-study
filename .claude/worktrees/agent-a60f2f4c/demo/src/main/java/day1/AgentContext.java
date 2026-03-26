package day1;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentContext {
    public static final int maxRoundNum = 10;
    private int roundNum;
    private List<ThoughtAndActionInfo> thoughtAndActionInfos = new ArrayList<>();

    public boolean exceedMaxRound() {
        return roundNum > maxRoundNum;
    }

    public int addRoundNum() {
        roundNum ++ ;
        return roundNum;
    }

    public void addThoughtAndActionInfo(ThoughtAndActionInfo info) {
        thoughtAndActionInfos.add(info);
    }



}