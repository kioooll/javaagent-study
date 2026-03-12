package day1;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
public  class ToolInfo {
    private String toolName;
    private String param;
}