package com.winniethepooh.hotelsystembackend.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hotel.agent")
public class AgentProperties {
    private String provider = "openai";
    private String model = "gpt-6-luna";
    private Openai openai = new Openai();
    private int maxToolCalls = 8;
    private int actionTtlMinutes = 10;
    private int sessionTtlMinutes = 30;
    private int maxTurns = 20;
    private int ratePerMinute = 10;
    private int timeoutSeconds = 60;

    @Data
    public static class Openai {
        private String apiKey = "";
    }
}
