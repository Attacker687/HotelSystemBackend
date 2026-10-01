package com.winniethepooh.hotelsystembackend.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "hotel.agent", name = "provider", havingValue = "fake")
public class FakeLlmClient implements LlmClient {
    private final Queue<List<AgentItem>> scripts = new ConcurrentLinkedQueue<>();
    private final List<List<AgentItem>> inputs = new ArrayList<>();

    public void enqueue(AgentItem... output) { scripts.add(List.of(output)); }
    public synchronized List<List<AgentItem>> inputs() { return List.copyOf(inputs); }
    public synchronized void reset() { scripts.clear(); inputs.clear(); }
    @Override public boolean available() { return true; }

    @Override
    public List<AgentItem> respond(String instructions, List<AgentItem> input, Consumer<String> onTextDelta, Duration timeout) {
        synchronized (this) { inputs.add(List.copyOf(input)); }
        List<AgentItem> output = scripts.poll();
        if (output == null) output = List.of(AgentItem.assistant("我可以帮您查房询价、预订、支付、取消客房订单、查订单和点餐。"));
        for (AgentItem item : output) {
            if (item.type() == AgentItem.Type.ASSISTANT && item.text() != null) {
                for (int start = 0; start < item.text().length(); start += 3)
                    onTextDelta.accept(item.text().substring(start, Math.min(start + 3, item.text().length())));
            }
        }
        return output;
    }
}
