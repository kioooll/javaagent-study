package day1;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;

public class WeatherTool implements Tool<WeatherTool.WeatherReq, WeatherTool.WeatherRes> {
    @Override
    public String getToolName() {
        return "天气查询";
    }

    @Override
    public String getDescription() {
        return "用来查询某地某日的天气";
    }

    @Override
    public String getToolParamDesc() {
        return """
                1. city -> 城市
                2. date -> 时间.如:2026-01-02 默认今天
                """;
    }

    @Override
    public WeatherReq convert2Input(String actionInput) {
        return JSONObject.parseObject(actionInput, WeatherReq.class);
    }

    @Override
    public String convert2Output(WeatherReq req, WeatherRes res) {
        return String.format("%s %s 的天气是 %s", req.city,req.date,res.getRes());
    }

    @Override
    public WeatherRes doExecute(WeatherReq weatherReq) {
        return new WeatherRes("26摄氏度");
    }

    @Data
    public static class WeatherReq {
        private String city;
        private String date;
    }

    @Data
    @AllArgsConstructor
    public static class WeatherRes {
        private String res;
    }

}
