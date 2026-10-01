package com.winniethepooh.hotelsystembackend.agent;

import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.time.Duration;

/** A request-thread deadline; native clients read it before each blocking operation. */
final class AgentDeadline implements AutoCloseable {
    private static final ThreadLocal<AgentDeadline> CURRENT = new ThreadLocal<>();
    private final AgentDeadline previous;
    private final long nanos;
    private final long epochMillis;

    private AgentDeadline(Duration budget) {
        previous = CURRENT.get();
        nanos = System.nanoTime() + budget.toNanos();
        epochMillis = System.currentTimeMillis() + budget.toMillis();
        CURRENT.set(this);
    }

    static AgentDeadline start(Duration budget) { return new AgentDeadline(budget); }
    static AgentDeadline current() { return CURRENT.get(); }
    long epochMillis() { return epochMillis; }
    boolean expired() { return System.nanoTime() >= nanos; }
    Duration remaining() {
        long left = nanos - System.nanoTime();
        if (left <= 0) throw new LlmException(true, null);
        return Duration.ofNanos(left);
    }
    int remainingMillis() { return (int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining().toMillis())); }
    int remainingSeconds() { return Math.max(1, (int) Math.ceil(remaining().toNanos() / 1_000_000_000d)); }
    static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof SQLTimeoutException
                    || cause instanceof io.lettuce.core.RedisCommandTimeoutException
                    || cause instanceof org.springframework.transaction.TransactionTimedOutException) return true;
        }
        return CURRENT.get() != null && CURRENT.get().expired();
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
