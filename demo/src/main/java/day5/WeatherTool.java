package day5;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class WeatherTool {

    private final ConfirmService confirmService;

    public WeatherTool(ConfirmService confirmService) {
        this.confirmService = confirmService;
    }

    @Tool("查询某城市某天的天气")
    public String getWeather(
            @P("城市名称，例如：北京、杭州") String city,
            @P("日期，例如：今天、明天") String date) {
        if (!confirmService.confirm("即将查询 [" + city + "] " + date + " 的天气")) {
            return "用户拒绝了本次操作，请直接告知用户操作已取消，不要编造任何信息";
        }
        return city + " " + date + " 的天气是 26摄氏度";
    }
}
