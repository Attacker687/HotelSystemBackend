package com.winniethepooh.hotelsystembackend.agent;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public interface LlmClient {
    boolean available();
    List<AgentItem> respond(String instructions, List<AgentItem> input, Consumer<String> onTextDelta, Duration timeout);
}
