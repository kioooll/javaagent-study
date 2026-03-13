package day3;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    public JsonObject getToolParamDesc() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject city = new JsonObject();
        city.addProperty("type", "string");
        city.addProperty("description", "城市名称，例如：北京、杭州");

        JsonObject date = new JsonObject();
        date.addProperty("type", "string");
        date.addProperty("description", "日期，例如：今天、明天、2024-01-01");

        JsonObject properties = new JsonObject();
        properties.add("city", city);
        properties.add("date", date);
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("city");
        required.add("date");
        schema.add("required", required);

        return schema;
    }

    @Override
    public WeatherReq convert2Input(String actionInput) {
        return JSONObject.parseObject(actionInput, WeatherReq.class);
    }

    @Override
    public String convert2Output(WeatherReq req, WeatherRes res) {
        return String.format("%s %s 的天气是 %s", req.city, req.date, res.getRes());
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
