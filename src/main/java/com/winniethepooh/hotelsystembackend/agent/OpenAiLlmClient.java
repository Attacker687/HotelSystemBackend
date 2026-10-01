package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.core.JsonSchemaLocalValidation;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "hotel.agent", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiLlmClient implements LlmClient {
    private static final ObjectMapper JSON = ObjectMappers.jsonMapper();
    private final AgentProperties props;
    private final Supplier<OpenAIClient> factory;
    private OpenAIClient client;

    @Autowired
    public OpenAiLlmClient(AgentProperties props) {
        this(props, () -> OpenAIOkHttpClient.builder().apiKey(props.getOpenai().getApiKey()).maxRetries(0).build());
    }

    OpenAiLlmClient(AgentProperties props, Supplier<OpenAIClient> factory) {
        this.props = props;
        this.factory = factory;
    }

    @Override
    public boolean available() {
        String key = props.getOpenai().getApiKey();
        return key != null && !key.isBlank();
    }

    private synchronized OpenAIClient client() {
        if (client == null) client = factory.get();
        return client;
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) client.close();
    }

    ResponseCreateParams buildRequest(String instructions, List<AgentItem> input) {
        List<ResponseInputItem> converted = new ArrayList<>();
        for (AgentItem item : input) {
            try {
                if (item.raw() != null && (item.type() == AgentItem.Type.REASONING
                        || item.type() == AgentItem.Type.ASSISTANT || item.type() == AgentItem.Type.FUNCTION_CALL)) {
                    converted.add(JSON.readValue(item.raw(), ResponseInputItem.class));
                    continue;
                }
                switch (item.type()) {
                    case USER, ASSISTANT, NOTE -> converted.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                            .role(item.type() == AgentItem.Type.ASSISTANT ? EasyInputMessage.Role.ASSISTANT : EasyInputMessage.Role.USER)
                            .content(item.type() == AgentItem.Type.NOTE ? "[系统通知] " + item.text() : item.text()).build()));
                    case FUNCTION_CALL -> converted.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                            .callId(item.callId()).name(item.name()).arguments(item.arguments()).build()));
                    case FUNCTION_CALL_OUTPUT -> converted.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                            .callId(item.callId()).output(item.output()).build()));
                    case REASONING -> { /* 缺 raw 的推理项没有可回放的加密内容。 */ }
                }
            } catch (JsonProcessingException e) { throw new LlmException(false, e); }
        }
        return ResponseCreateParams.builder().model(props.getModel()).instructions(instructions)
                .inputOfResponse(converted).store(false).addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
                // 对话场景以首字时间为先：默认推理强度下约一半回复首字超过 3 秒，low 足够完成工具选择与追问。
                .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
                .addTool(AgentTools.SearchAvailableRooms.class).addTool(AgentTools.GetPriceQuote.class)
                .addTool(noArgumentsTool(AgentTools.ListMyOrders.class)).addTool(noArgumentsTool(AgentTools.ListMenu.class))
                .addTool(AgentTools.ProposeBooking.class).addTool(AgentTools.ProposePayment.class).addTool(AgentTools.ProposeCancel.class)
                .addTool(AgentTools.ProposeMealOrder.class).build();
    }

    private FunctionTool noArgumentsTool(Class<?> type) {
        // SDK 4.73.0 的 Class 派生会省略空 properties，且本地校验拒绝无参 schema。
        FunctionTool tool = ResponseCreateParams.builder().model(props.getModel()).addTool(type, JsonSchemaLocalValidation.NO)
                .build().tools().orElseThrow().get(0).asFunction();
        return tool.toBuilder().parameters(FunctionTool.Parameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .putAdditionalProperty("properties", JsonValue.from(Map.of()))
                .putAdditionalProperty("required", JsonValue.from(List.of())).build()).build();
    }

    List<AgentItem> convertOutput(List<ResponseOutputItem> output) {
        List<AgentItem> converted = new ArrayList<>();
        for (ResponseOutputItem item : output) {
            try {
                String raw = JSON.writeValueAsString(item);
                if (item.isReasoning()) {
                    converted.add(new AgentItem(AgentItem.Type.REASONING, null, null, null, null, null, raw));
                } else if (item.isMessage()) {
                    String text = item.asMessage().content().stream().map(content -> content.isOutputText()
                            ? content.asOutputText().text() : content.asRefusal().refusal()).collect(Collectors.joining());
                    converted.add(new AgentItem(AgentItem.Type.ASSISTANT, text, null, null, null, null, raw));
                } else if (item.isFunctionCall()) {
                    ResponseFunctionToolCall call = item.asFunctionCall();
                    converted.add(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, call.callId(), call.name(), call.arguments(), null, raw));
                } else { throw new LlmException(false, new IllegalStateException("Unsupported model output item")); }
            } catch (JsonProcessingException e) { throw new LlmException(false, e); }
        }
        return converted;
    }

    @Override
    public List<AgentItem> respond(String instructions, List<AgentItem> input, Consumer<String> onTextDelta, Duration timeout) {
        if (!available()) throw new LlmException(false, null);
        if (timeout.isNegative() || timeout.isZero()) throw new LlmException(true, null);
        long started = System.nanoTime();
        ResponseAccumulator accumulator = ResponseAccumulator.create();
        boolean[] streamedText = {false};
        java.io.UncheckedIOException[] disconnected = {null};
        Object[] tokens = {"unavailable", "unavailable", "unavailable"};
        String result = "MODEL_UNAVAILABLE";
        Consumer<String> emit = text -> {
            try { onTextDelta.accept(text); }
            catch (java.io.UncheckedIOException e) { disconnected[0] = e; throw e; }
        };
        try (var stream = client().responses().createStreaming(buildRequest(instructions, input), RequestOptions.builder().timeout(timeout).build())) {
            stream.stream().forEach(event -> {
                if (System.nanoTime() - started >= timeout.toNanos()) throw new LlmException(true, null);
                if (event.isError() || event.isFailed() || event.isIncomplete()) throw new LlmException(false, null);
                accumulator.accumulate(event);
                event.outputTextDelta().ifPresent(delta -> { streamedText[0] = true; emit.accept(delta.delta()); });
            });
            Response response = accumulator.response();
            response.usage().ifPresent(usage -> {
                tokens[0] = usage.inputTokens(); tokens[1] = usage.outputTokens(); tokens[2] = usage.inputTokensDetails().cachedTokens();
            });
            List<AgentItem> output = convertOutput(response.output());
            if (!streamedText[0]) output.stream().filter(item -> item.type() == AgentItem.Type.ASSISTANT)
                    .forEach(item -> emit.accept(item.text()));
            result = "SUCCESS";
            return output;
        } catch (LlmException e) { result = e.isTimeout() ? "TIMEOUT" : "MODEL_UNAVAILABLE"; throw e; }
        catch (RuntimeException e) {
            if (disconnected[0] != null) throw disconnected[0];
            boolean timedOut = System.nanoTime() - started >= timeout.toNanos();
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedIOException || cause instanceof HttpTimeoutException || cause instanceof TimeoutException)
                    timedOut = true;
            }
            result = timedOut ? "TIMEOUT" : "MODEL_UNAVAILABLE";
            throw new LlmException(timedOut, e);
        } finally {
            if (disconnected[0] == null) log.info("agent.llm user={} model={} input={} output={} cached={} ms={} result={}",
                    BaseContext.getCurrentId(), props.getModel(), tokens[0], tokens[1], tokens[2],
                    (System.nanoTime() - started) / 1_000_000, result);
        }
    }
}
