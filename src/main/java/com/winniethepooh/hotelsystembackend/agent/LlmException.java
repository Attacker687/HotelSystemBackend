package com.winniethepooh.hotelsystembackend.agent;

public class LlmException extends RuntimeException {
    private final boolean timeout;

    public LlmException(boolean timeout, Throwable cause) {
        super(timeout ? "回复超时，请重试" : "智能助手暂不可用，请稍后再试", cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() { return timeout; }
}
