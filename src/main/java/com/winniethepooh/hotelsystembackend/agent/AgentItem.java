package com.winniethepooh.hotelsystembackend.agent;

public record AgentItem(Type type, String text, String callId, String name, String arguments, String output, String raw) {
    public enum Type { USER, ASSISTANT, REASONING, FUNCTION_CALL, FUNCTION_CALL_OUTPUT, NOTE }

    public static AgentItem user(String text) {
        return new AgentItem(Type.USER, text, null, null, null, null, null);
    }

    public static AgentItem assistant(String text) {
        return new AgentItem(Type.ASSISTANT, text, null, null, null, null, null);
    }
}
