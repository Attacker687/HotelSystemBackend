package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** 测试基建自检：后续批次依赖的基类、fixture、登录辅助、SQL 计数、测试 profile。 */
class InfrastructureIT extends IntegrationTestBase {

    @Autowired
    private ObjectProvider<ScheduledTaskHolder> scheduledTasks;
    @Autowired
    private RoomMapper roomMapper;

    @Test
    void infra_baseAccountsLoginAndTokenStoredInRedis() {
        for (Fixtures.Account a : new Fixtures.Account[]{base.userA(), base.userB(), base.manager(), base.front(), base.restaurant()}) {
            String token = login(a);
            assertThat(token).isNotBlank();
            assertThat(redis.hasKey(token)).as("token of %s in redis", a.login()).isTrue();
        }
    }

    @Test
    void infra_baseDataMatchesStrategy() {
        assertThat(fx.count("user")).isEqualTo(2);
        assertThat(fx.count("individual")).isEqualTo(2);
        assertThat(fx.count("staff")).isEqualTo(3);
        assertThat(fx.count("room")).isEqualTo(15);
        assertThat(fx.count("room", "id in (0, 1, 2)")).isZero();
        assertThat(jdbc.queryForObject("select count(distinct floor) from room", Integer.class)).isEqualTo(5);
        assertThat(fx.count("dish", "is_deleted = 0 and status = 1")).isEqualTo(1);
        assertThat(fx.count("room_order") + fx.count("meal_order") + fx.count("price_calendar")).isZero();
    }

    @Test
    void infra_sqlCounterCountsEachStatementOnce() {
        roomMapper.getExistFloors();
        assertThat(sql.statements()).containsExactly("select distinct floor from room where is_deleted = 0");

        sql.reset();
        assertThat(sql.count()).isZero();
    }

    @Test
    void infra_sqlCounterSeesStatementsRunOnTomcatThreads() {
        String token = login(base.manager());
        sql.reset();

        Resp r = get("/rooms", token);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(r.data().path("total").asInt()).isEqualTo(15);
        assertThat(sql.statements()).anyMatch(s -> s.startsWith("select * from room"));
    }

    @Test
    void infra_resetClearsTablesAndRedis() {
        long order = fx.roomOrder(base.userA().id(), 1, base.room("101"), LocalDateTime.now(), LocalDateTime.now().plusDays(1),
                null, 0, 0);
        assertThat(order).isPositive();
        login(base.userA());

        fx.reset();

        assertThat(fx.count("room_order") + fx.count("user") + fx.count("room")).isZero();
        assertThat(redis.keys("*")).isEmpty();
    }

    @Test
    void infra_testProfileDisablesSchedulerCron() {
        assertThat(scheduledTasks.stream().flatMap(h -> h.getScheduledTasks().stream())).isEmpty();
    }

    @Test
    void infra_databaseAndJvmUseShanghaiClock() {
        assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(jdbc.queryForObject("select @@session.time_zone", String.class)).isEqualTo("+08:00");
        LocalDateTime db = jdbc.queryForObject("select now()", LocalDateTime.class);
        assertThat(db).isBetween(LocalDateTime.now().minusSeconds(5), LocalDateTime.now().plusSeconds(5));
    }

    @Test
    void infra_createdMinutesAgoUsesDatabaseClock() {
        long order = fx.roomOrder(base.userA().id(), 1, base.room("101"), LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2), null, 0, 0);

        fx.createdMinutesAgo("room_order", order, 16);

        assertThat(fx.count("room_order", "id = ? and created_at < NOW() - INTERVAL 15 MINUTE", order)).isEqualTo(1);
        assertThat(fx.count("room_order", "id = ? and created_at > NOW() - INTERVAL 17 MINUTE", order)).isEqualTo(1);
    }
}
