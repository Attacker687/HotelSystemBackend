package com.winniethepooh.hotelsystembackend.agent;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class AgentDeadlineTest {
    @Test
    void nativeLettuceTimeoutSourceUsesDecreasingBudgetOnlyOnTheRequestThread() throws Exception {
        var builder = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(8));
        new AgentIoConfiguration().agentCommandDeadlines().customize(builder);
        var source = builder.build().getClientOptions().orElseThrow().getTimeoutOptions().getSource();
        assertThat(source.getTimeUnit()).isEqualTo(java.util.concurrent.TimeUnit.NANOSECONDS);
        assertThat(source.getTimeout(null)).isEqualTo(Duration.ofSeconds(8).toNanos());
        try (AgentDeadline deadline = AgentDeadline.start(Duration.ofSeconds(1))) {
            long first = source.getTimeout(null); Thread.sleep(20); long second = source.getTimeout(null);
            assertThat(first).isPositive().isLessThan(Duration.ofSeconds(1).toNanos()); assertThat(second).isLessThan(first);
            AtomicLong other = new AtomicLong(); Thread thread = new Thread(() -> other.set(source.getTimeout(null)));
            thread.start(); thread.join(1000); assertThat(other).hasValue(Duration.ofSeconds(8).toNanos());
        }
        assertThat(AgentDeadline.current()).isNull(); assertThat(source.getTimeout(null)).isEqualTo(Duration.ofSeconds(8).toNanos());
    }

    @Test
    void expiredDeadlineRefusesBeforeDispatchAndIsRemovedAfterTheRequest() throws Exception {
        try (AgentDeadline deadline = AgentDeadline.start(Duration.ofMillis(20))) {
            Thread.sleep(30);
            assertThat(deadline.expired()).isTrue(); assertThatThrownBy(deadline::remaining).isInstanceOf(LlmException.class);
        }
        assertThat(AgentDeadline.current()).isNull();
    }

    @Test
    void nestedScopeRestoresThePreviousDeadline() {
        try (AgentDeadline outer = AgentDeadline.start(Duration.ofSeconds(2))) {
            try (AgentDeadline inner = AgentDeadline.start(Duration.ofSeconds(1))) { assertThat(AgentDeadline.current()).isSameAs(inner); }
            assertThat(AgentDeadline.current()).isSameAs(outer);
        }
        assertThat(AgentDeadline.current()).isNull();
    }
}
