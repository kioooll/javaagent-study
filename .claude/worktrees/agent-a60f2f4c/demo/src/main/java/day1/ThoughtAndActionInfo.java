package day1;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class ThoughtAndActionInfo {

    private ThoughtInfo thoughtInfo;

    private List<ToolAndResultInfo> toolAndResultInfos;

    public String getPrompt() {
        return PromptConstant.historyThoughtTemplate.formatted(thoughtInfo.getThought(),
                toolAndResultInfos.stream()
                        .map(ToolAndResultInfo::getPrompt).collect(Collectors.joining("\n"))
        );
    }

}
