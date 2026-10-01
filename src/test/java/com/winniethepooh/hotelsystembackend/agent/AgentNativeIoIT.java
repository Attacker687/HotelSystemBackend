package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import javax.sql.DataSource;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

class AgentNativeIoIT extends IntegrationTestBase {
    @Test
    void edgeF2_springBeanDestructionClosesTheSingleLivePool() {
        HikariDataSource live;
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", HikariDataSource.class, this::uninitializedPool);
        context.register(AgentIoConfiguration.class); context.refresh();
        live = (HikariDataSource) ((AgentIoConfiguration.DeadlineDataSource) context.getBean(DataSource.class)).getTargetDataSource();
        assertThat(live.isClosed()).isFalse(); context.close();
        assertThat(live.isClosed()).isTrue();
    }
    @Test
    void edgeF1_roundAndNoteEachUseExactlyOneNativeEvalRpushAndTtl() throws Exception {
        SessionStore store = new SessionStore(redis, new ObjectMapper(), new AgentProperties());
        List<AgentItem> items = List.of(AgentItem.user("本轮"), new AgentItem(AgentItem.Type.REASONING, null, null, null, null, null, "{\"type\":\"reasoning\",\"encrypted_content\":\"opaque+/==\"}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "list_menu", "{}", null, "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"list_menu\",\"arguments\":\"{}\"}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c1", null, null, "{\"ok\":true}", null), AgentItem.assistant("回复\n原样"),
                new AgentItem(AgentItem.Type.NOTE, "系统通知", null, null, null, null, null));
        for (boolean note : List.of(false, true)) {
            Properties before = stats();
            if (note) store.appendNote(7, "native", "追加通知"); else store.append(7, "native", items);
            Properties after = stats();
            for (String command : List.of("eval", "rpush", "pexpire")) assertThat(calls(after, command) - calls(before, command)).as(command).isEqualTo(1);
            assertThat(calls(after, "evalsha") - calls(before, "evalsha")).isZero();
            assertThat(redis.getExpire("agent:session:7:native")).isBetween(1795L, 1800L);
        }
        assertThat(store.window(7, "native").subList(0, items.size())).isEqualTo(items);
        assertThat(store.window(7, "native").get(items.size()).text()).isEqualTo("追加通知");
    }

    private Properties stats() { return redis.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("commandstats")); }
    private long calls(Properties stats, String command) {
        String info = stats.getProperty("cmdstat_" + command, "calls=0,usec=0");
        return Long.parseLong(info.substring(info.indexOf("calls=") + 6, info.indexOf(',')));
    }
    @Test
    void edgeF2_firstPoolUsesCompleteConfigurationAndReturnsRestoredConnections() throws Exception {
        HikariDataSource configured = uninitializedPool();
        assertThat(configured.getHikariPoolMXBean()).isNull();
        try (var data = new AgentIoConfiguration.DeadlineDataSource(configured)) {
            HikariDataSource live = (HikariDataSource) data.getTargetDataSource();
            assertThat(configured.isClosed()).isTrue(); assertThat(live.getHikariPoolMXBean()).isNotNull();
            assertThat(live.getJdbcUrl()).isEqualTo(configured.getJdbcUrl()); assertThat(live.getUsername()).isEqualTo(configured.getUsername());
            assertThat(live.getPassword()).isEqualTo(configured.getPassword()); assertThat(live.getMaximumPoolSize()).isEqualTo(1);
            assertThat(live.getMinimumIdle()).isZero(); assertThat(live.getConnectionTimeout()).isEqualTo(1200);
            assertThat(live.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
            try (AgentDeadline deadline = AgentDeadline.start(Duration.ofSeconds(2)); Connection connection = data.getConnection()) {
                assertThat(connection.getNetworkTimeout()).isBetween(1, 2000);
                try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT 1")) { assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1); }
            }
            assertThat(AgentDeadline.current()).isNull();
            try (Connection connection = data.getConnection()) { assertThat(connection.getNetworkTimeout()).isZero(); }
            data.close(); assertThat(live.isClosed()).isTrue();
            assertThatThrownBy(data::getConnection).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void edgeF2_nativePoolBorrowHonorsOnlyTheCurrentRequestsBudget(boolean agent) throws Exception {
        try (var data = new AgentIoConfiguration.DeadlineDataSource(uninitializedPool()); Connection held = data.getConnection()) {
            long start = System.nanoTime();
            try (AgentDeadline deadline = agent ? AgentDeadline.start(Duration.ofMillis(350)) : null) {
                assertThatThrownBy(data::getConnection).isInstanceOf(java.sql.SQLTransientConnectionException.class);
            }
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsed).isBetween(agent ? 250L : 1100L, agent ? 900L : 1800L);
            assertThat(AgentDeadline.current()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void edgeF1_nativeServerRefusalNeverAppendsOrChangesExistingTtl(boolean existing) throws Exception {
        String key = "agent:session:7:refused";
        if (existing) { redis.opsForList().rightPushAll(key, "old-user", "old-assistant"); redis.expire(key, Duration.ofSeconds(60)); }
        AgentProperties props = new AgentProperties(); props.setSessionTtlMinutes(-1);
        SessionStore store = new SessionStore(redis, new ObjectMapper(), props);
        assertThatThrownBy(() -> store.append(7, "refused", List.of(AgentItem.user("failed"), AgentItem.assistant("failed")))).isInstanceOf(IllegalStateException.class);
        if (existing) { assertThat(redis.opsForList().range(key, 0, -1)).containsExactly("old-user", "old-assistant"); assertThat(redis.getExpire(key)).isBetween(1L, 60L); }
        else assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void edgeF1_nativeAclDeniedTtlIsCheckedBeforeRpush() throws Exception {
        String key = "agent:session:7:acl";
        redis.opsForList().rightPushAll(key, "old-user", "old-assistant"); redis.expire(key, Duration.ofSeconds(60));
        var acl = REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "repair", "on", ">repair-test", "~agent:session:*", "+@all", "-pexpire");
        assertThat(acl.getExitCode()).isZero(); assertThat(acl.getStdout().trim()).isEqualTo("OK");
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setUsername("repair"); config.setPassword("repair-test");
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet(); factory.start();
        try {
            StringRedisTemplate restricted = new StringRedisTemplate(factory);
            SessionStore store = new SessionStore(restricted, new ObjectMapper(), new AgentProperties());
            assertThatThrownBy(() -> store.appendNote(7, "acl", "failed note")).isInstanceOf(IllegalStateException.class);
            assertThat(redis.opsForList().range(key, 0, -1)).containsExactly("old-user", "old-assistant");
            assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        } finally { factory.destroy(); REDIS.execInContainer("redis-cli", "ACL", "DELUSER", "repair"); }
    }

    private HikariDataSource uninitializedPool() {
        HikariDataSource data = new HikariDataSource();
        data.setJdbcUrl(MYSQL.getJdbcUrl()); data.setUsername(MYSQL.getUsername()); data.setPassword(MYSQL.getPassword());
        data.setMaximumPoolSize(1); data.setMinimumIdle(0); data.setConnectionTimeout(1200);
        data.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        return data;
    }
}
