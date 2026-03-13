package day3;

public class Main {

    public static void main(String[] args) throws Exception {
        Agent agent = new Agent();
        agent.registerTool(new WeatherTool());
        agent.agent(new Agent.AgentInput("今天杭州天气"), new AgentContext());
    }
}
