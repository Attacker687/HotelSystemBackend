package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class AgentService {
    private final AgentProperties props;
    private final LlmClient llm;
    private final ObjectMapper json;
    private final Clock clock;
    private final String fixedPrompt;

    @Autowired
    public AgentService(AgentProperties props, LlmClient llm, ObjectMapper json) {
        this(props, llm, json, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    AgentService(AgentProperties props, LlmClient llm, ObjectMapper json, Clock clock) {
        this.props = props;
        this.llm = llm;
        this.json = json;
        this.clock = clock;
        try (var resource = new ClassPathResource("agent/system-prompt.txt").getInputStream()) {
            fixedPrompt = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    String instructions() {
        LocalDate today = LocalDate.now(clock);
        return fixedPrompt + "今天是 " + today + "（" + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "），时区 Asia/Shanghai";
    }

    public void chat(Integer userId, String sessionId, String message, HttpServletResponse response) {
        if (!llm.available()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "智能助手暂不可用，请稍后再试");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        try {
            send(response, "status", Map.of("text", "正在思考…"));
            try {
                llm.respond(instructions(), List.of(AgentItem.user(message)), text -> {
                    try { send(response, "delta", Map.of("text", text)); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                }, Duration.ofSeconds(props.getTimeoutSeconds()));
            } catch (LlmException e) {
                send(response, "error", Map.of("code", e.isTimeout() ? "TIMEOUT" : "MODEL_UNAVAILABLE", "msg", e.getMessage()));
            } catch (UncheckedIOException e) { return; }
            catch (RuntimeException e) {
                log.error("agent.chat user={} failed", userId, e);
                send(response, "error", Map.of("code", "INTERNAL", "msg", "系统繁忙，请稍后再试"));
            }
            send(response, "done", Map.of("toolCalls", 0));
        } catch (IOException e) { /* 浏览器断开，结束本次请求。 */ }
    }

    private void send(HttpServletResponse response, String event, Object data) throws IOException {
        response.getWriter().write("event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n");
        response.flushBuffer();
    }
}
