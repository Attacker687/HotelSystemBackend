package com.winniethepooh.hotelsystembackend.agent;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.protocol.RedisCommand;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

@Configuration(proxyBeanMethods = false)
class AgentIoConfiguration {
    @Bean
    LettuceClientConfigurationBuilderCustomizer agentCommandDeadlines() {
        return builder -> {
            var configured = builder.build();
            var options = configured.getClientOptions().orElseGet(ClientOptions::create);
            var timeout = TimeoutOptions.builder().timeoutCommands().timeoutSource(new TimeoutOptions.TimeoutSource() {
                @Override public long getTimeout(RedisCommand<?, ?, ?> command) {
                    AgentDeadline deadline = AgentDeadline.current();
                    return deadline == null ? configured.getCommandTimeout().toNanos() : deadline.remaining().toNanos();
                }
                @Override public TimeUnit getTimeUnit() { return TimeUnit.NANOSECONDS; }
            }).build();
            builder.clientOptions(options.mutate().timeoutOptions(timeout).build());
        };
    }

    @Bean
    static DestructionAwareBeanPostProcessor agentJdbcDeadlines() {
        return new DestructionAwareBeanPostProcessor() {
            private final ConcurrentHashMap<String, DeadlineDataSource> wrapped = new ConcurrentHashMap<>();
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof HikariDataSource hikari)) return bean;
                DeadlineDataSource data = new DeadlineDataSource(hikari);
                wrapped.put(name, data);
                return data;
            }
            @Override public boolean requiresDestruction(Object bean) { return bean instanceof HikariDataSource; }
            @Override public void postProcessBeforeDestruction(Object bean, String name) {
                DeadlineDataSource data = wrapped.remove(name);
                if (data != null) data.close();
            }
        };
    }

    /** One active native pool; the configuration copy avoids a blocking first-request pool initialization. */
    static final class DeadlineDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource hikari;

        DeadlineDataSource(HikariDataSource configured) {
            if (configured.getHikariPoolMXBean() == null) {
                HikariConfig copy = new HikariConfig();
                configured.copyStateTo(copy);
                copy.setInitializationFailTimeout(-1);
                hikari = new HikariDataSource(copy);
                configured.close();
            } else hikari = configured;
            setTargetDataSource(hikari);
        }

        @Override public Connection getConnection() throws SQLException {
            AgentDeadline deadline = AgentDeadline.current();
            if (deadline == null) return hikari.getConnection();
            Connection connection = ((HikariPool) hikari.getHikariPoolMXBean()).getConnection(deadline.remainingMillis());
            try {
                refresh(connection, deadline);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    // Rollback and close must still clean up an expired transaction; Hikari restores dirty timeouts.
                    if (method.getName().equals("close") || method.getName().equals("rollback")) {
                        if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, deadline.expired() ? 1 : deadline.remainingMillis());
                    } else if (!method.getName().equals("isClosed") && !method.getName().equals("getNetworkTimeout")) refresh(connection, deadline);
                    try {
                        Object result = method.invoke(connection, args);
                        if (result instanceof Statement statement) return timedStatement(statement, deadline);
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            } catch (RuntimeException | SQLException e) { connection.close(); throw e; }
        }

        private static Object timedStatement(Statement statement, AgentDeadline deadline) {
            Class<?> type = statement instanceof java.sql.CallableStatement ? java.sql.CallableStatement.class
                    : statement instanceof java.sql.PreparedStatement ? java.sql.PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().startsWith("execute")) {
                    refresh(statement.getConnection(), deadline);
                    statement.setQueryTimeout(deadline.remainingSeconds());
                }
                try { return method.invoke(statement, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }

        private static void refresh(Connection connection, AgentDeadline deadline) throws SQLException {
            connection.setNetworkTimeout(Runnable::run, deadline.remainingMillis());
        }

        @Override public void close() { hikari.close(); }
    }
}
