package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {
    private final AgentProperties props = new AgentProperties();
    private final LlmClient llm = mock(LlmClient.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final AgentTools tools = mock(AgentTools.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private AgentService service;
    private final String session = UUID.randomUUID().toString();

    @BeforeEach
    void prepare() {
        when(llm.available()).thenReturn(true); when(redis.opsForValue().increment(anyString())).thenReturn(1L);
        service = new AgentService(props, llm, new ObjectMapper(), sessions, tools, redis);
    }

    @Test
    void s06ac4_everyCallGetsDecreasingRemainingBudgetAndOnlySuccessfulTurnIsSaved() throws Exception {
        AgentItem call = new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "list_menu", "{}", null, null);
        when(llm.respond(any(), any(), any(), any())).thenAnswer(i -> { Thread.sleep(40); return List.of(call); }).thenReturn(List.of(AgentItem.assistant("完成")));
        when(tools.execute(any(), any(), any())).thenReturn(new AgentTools.ToolResult("{\"ok\":true}", null));
        MockHttpServletResponse response = new MockHttpServletResponse(); service.chat(7, session, "新消息", response);
        ArgumentCaptor<Duration> budgets = ArgumentCaptor.forClass(Duration.class); verify(llm, times(2)).respond(any(), any(), any(), budgets.capture());
        assertThat(budgets.getAllValues().get(0)).isPositive().isLessThan(Duration.ofSeconds(60));
        assertThat(budgets.getAllValues().get(1)).isPositive().isLessThan(budgets.getAllValues().get(0).minusMillis(30));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<AgentItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(sessions).append(eq(7), eq(session), saved.capture());
        assertThat(saved.getValue()).extracting(AgentItem::type).containsExactly(AgentItem.Type.USER, AgentItem.Type.FUNCTION_CALL, AgentItem.Type.FUNCTION_CALL_OUTPUT, AgentItem.Type.ASSISTANT);
        assertThat(response.getContentAsString()).endsWith("event: done\ndata: {\"toolCalls\":1}\n\n");
    }

    @Test
    void s06ac3_unavailablePrecedesQuotaAndAnySseHeaders() {
        when(llm.available()).thenReturn(false); MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.chat(7, session, "你好", response)).isInstanceOf(BusinessException.class);
        verify(redis.opsForValue(), never()).increment(anyString()); verifyNoInteractions(sessions, tools);
        assertThat(response.getContentType()).isNull(); assertThat(response.isCommitted()).isFalse();
    }

    @Test
    void s06ac3_firstIncrementExpiresOnceAndEleventhRejectsBeforeModel() {
        when(redis.opsForValue().increment(anyString())).thenReturn(1L, 2L, 11L);
        when(llm.respond(any(), any(), any(), any())).thenReturn(List.of(AgentItem.assistant("回复")));
        service.chat(7, session, "首条", new MockHttpServletResponse()); service.chat(7, session, "次条", new MockHttpServletResponse());
        MockHttpServletResponse limited = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.chat(7, session, "限流", limited)).isInstanceOf(BusinessException.class).hasMessage("消息太频繁，请稍后再试");
        verify(redis, times(1)).expire("agent:rate:7", Duration.ofSeconds(60)); verify(llm, times(2)).respond(any(), any(), any(), any());
        assertThat(limited.isCommitted()).isFalse(); assertThat(limited.getContentType()).isNull();
    }

    @Test
    void s06ac4_internalFailureEndsWithErrorDoneAndDoesNotSave() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentService.class); ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
        when(sessions.window(any(), any())).thenThrow(new IllegalStateException("private message 110101199001010015"));
        MockHttpServletResponse response = new MockHttpServletResponse(); service.chat(7, session, "私密消息", response);
        assertThat(response.getContentAsString()).contains("\"code\":\"INTERNAL\"").endsWith("event: done\ndata: {\"toolCalls\":0}\n\n").doesNotContain("private", "私密消息");
        verify(sessions, never()).append(any(), any(), any()); verify(llm, never()).respond(any(), any(), any(), any());
        assertThat(logs.list).hasSize(1); assertThat(logs.list.get(0).getFormattedMessage()).contains("agent.chat user=7", "IllegalStateException")
                .doesNotContain("private", "110101199001010015", "私密消息"); assertThat(logs.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test
    void s06ac4_disconnectedDeltaStopsQuietlyAndDoesNotSave() throws Exception {
        when(llm.respond(any(), any(), any(), any())).thenAnswer(i -> {
            Consumer<String> delta = i.getArgument(2); delta.accept("回复"); return List.of(AgentItem.assistant("回复"));
        });
        MockHttpServletResponse response = new MockHttpServletResponse() {
            private int flushes;
            @Override public void flushBuffer() { if (++flushes > 1) throw new UncheckedIOException(new IOException("disconnected")); super.flushBuffer(); }
        };
        service.chat(7, session, "你好", response);
        assertThat(response.getContentAsString()).doesNotContain("event: error", "event: done");
        verify(sessions, never()).append(any(), any(), any());
    }
}
