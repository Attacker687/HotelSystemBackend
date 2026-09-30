package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 测试基建自检：后续批次依赖的基类、fixture（testplan.json strategy.data）、登录辅助、SQL 计数、测试 profile。 */
class InfrastructureIT extends IntegrationTestBase {

    @Autowired
    private ObjectProvider<ScheduledTaskHolder> scheduledTasks;
    @Autowired
    private RoomMapper roomMapper;

    @Test
    void infra_allBaseAccountsCanLoginAndTokenStoredInRedis() {
        for (String alias : List.of("A", "B", "C", "L1", "L2", "L3", "L4", "L5", "L6")) {
            String token = login(base.user(alias));
            assertThat(redis.hasKey(token)).as("token of user %s in redis", alias).isTrue();
        }
        for (String account : List.of("it_manager", "it_front", "it_restaurant", "it_to_delete", "it_to_disable", "it_legacy_md5")) {
            assertThat(login(base.staff(account))).as("staff %s", account).isNotBlank();
        }
    }

    @Test
    void infra_baseDataMatchesStrategyDataTable() {
        // 账号：住客 A/B/C、L1～L6 各带一条同名入住人；员工 6 个
        assertThat(jdbc.queryForList("select phone from user order by id", String.class)).containsExactly(
                "17000000001", "17000000002", "17000000003",
                "17000000011", "17000000012", "17000000013", "17000000014", "17000000015", "17000000016");
        assertThat(jdbc.queryForObject("select concat(name, '/', id_card_number, '/', email) from user where phone = '17000000001'",
                String.class)).isEqualTo("测试住客甲/110101199001010015/a@example.test");
        assertThat(jdbc.queryForObject("select count(*) from individual i join user u on i.phone = u.phone "
                + "and i.name = u.name and i.id_card_number = u.id_card_number", Integer.class)).isEqualTo(9);
        assertThat(fx.count("individual")).isEqualTo(9);
        assertThat(jdbc.queryForObject("select password from user where phone = '17000000003'", String.class))
                .isEqualTo(Fixtures.legacyMd5(Fixtures.PASSWORD));
        assertThat(jdbc.queryForList("select account, role, status, is_deleted from staff order by id"))
                .extracting(m -> m.get("account"), m -> m.get("role"), m -> m.get("status"), m -> ((Number) m.get("is_deleted")).intValue())
                .containsExactly(
                        tuple("it_manager", 1, 1, 0), tuple("it_front", 2, 1, 0), tuple("it_restaurant", 3, 1, 0),
                        tuple("it_to_delete", 2, 1, 0), tuple("it_to_disable", 2, 1, 0), tuple("it_legacy_md5", 2, 1, 0));

        // 房间：10 间，显式 id、房号、房型、楼层、房态
        assertThat(jdbc.queryForList("select id, room_number, room_type, floor, status, capacity from room order by id"))
                .extracting(m -> ((Number) m.get("id")).longValue(), m -> m.get("room_number"), m -> m.get("room_type"),
                        m -> m.get("floor"), m -> m.get("status"), m -> m.get("capacity"))
                .containsExactly(
                        tuple(1L, "1001", 2, 1, 0, 2),
                        tuple(101L, "1101", 0, 1, 0, 2), tuple(102L, "1102", 0, 1, 0, 2), tuple(103L, "1103", 0, 1, 0, 2),
                        tuple(104L, "2101", 1, 2, 0, 2), tuple(105L, "2102", 0, 2, 0, 2), tuple(106L, "3101", 2, 3, 0, 2),
                        tuple(107L, "4101", 1, 4, 1, 2), tuple(108L, "4102", 1, 4, 2, 2), tuple(109L, "5101", 2, 5, 3, 2));
        assertThat(base.room("R1").number()).isEqualTo("1101");
        assertThat(base.room("D1").id()).isEqualTo(1L);
        assertThat(base.room("S1").type()).isEqualTo(2);

        // 分类与菜品 X/Y/Z
        assertThat(jdbc.queryForList("select id, name, price, status, is_deleted, category_id from dish order by id"))
                .extracting(m -> ((Number) m.get("id")).intValue(), m -> m.get("name"), m -> m.get("price"),
                        m -> m.get("status"), m -> ((Number) m.get("is_deleted")).intValue(), m -> m.get("category_id"))
                .containsExactly(
                        tuple(1, "测试菜品X", new BigDecimal("38.00"), 1, 0, 1),
                        tuple(2, "测试菜品Y", new BigDecimal("28.00"), 1, 1, 1),
                        tuple(3, "测试菜品Z", new BigDecimal("18.00"), 0, 0, 1));
        assertThat(jdbc.queryForObject("select name from category where id = 1", String.class)).isEqualTo("测试分类");
        assertThat(base.dish("X").id()).isEqualTo(1L);

        assertThat(fx.count("room_order") + fx.count("meal_order") + fx.count("price_calendar")).isZero();
    }

    @Test
    void infra_resetRestartsAutoIncrement() {
        long first = fx.roomOrder(base.user("A").id(), 1, base.room("R1").id(), LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2), null, 0, 0);
        login(base.user("A"));

        fx.reset();
        assertThat(fx.count("room_order") + fx.count("user") + fx.count("room")).isZero();
        assertThat(redis.keys("*")).isEmpty();

        Fixtures.Base again = fx.seedBase();
        long second = fx.roomOrder(again.user("A").id(), 1, again.room("R1").id(), LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2), null, 0, 0);
        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(1L);
        assertThat(again.user("A").id()).isEqualTo(base.user("A").id()).isEqualTo(1);
        assertThat(again.dish("X").id()).isEqualTo(1L);
    }

    @Test
    void infra_standardRequestBodiesAreAcceptedByCurrentApi() {
        String token = login(base.user("A"));
        LocalDate d = LocalDate.now().plusDays(10);

        Resp room = post("/order", token, Fixtures.roomOrderBody(base.room("R1").number(),
                d.atTime(14, 0), d.plusDays(1).atTime(12, 0)));
        Resp meal = post("/order/meal-order", token, Fixtures.mealOrderBody());
        Resp reg = post("/user/register", null, Fixtures.registration("N"));

        assertThat(room.code()).as(room.body().toString()).isZero();
        assertThat(meal.code()).as(meal.body().toString()).isZero();
        assertThat(reg.code()).as(reg.body().toString()).isZero();
        assertThat(fx.count("individual", "name = '测试入住人零' and phone = '17000000100' and id_card_number = '110101199001010111'"))
                .isEqualTo(1);
    }

    @Test
    void infra_unknownAliasFailsFast() {
        assertThatThrownBy(() -> base.room("R9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("R9");
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
        assertThat(r.data().path("total").asInt()).isEqualTo(10);
        assertThat(sql.statements()).anyMatch(s -> s.startsWith("select * from room"));
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
        long order = fx.roomOrder(base.user("A").id(), 1, base.room("R1").id(), LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2), null, 0, 0);

        fx.createdMinutesAgo("room_order", order, 16);

        assertThat(fx.count("room_order", "id = ? and created_at < NOW() - INTERVAL 15 MINUTE", order)).isEqualTo(1);
        assertThat(fx.count("room_order", "id = ? and created_at > NOW() - INTERVAL 17 MINUTE", order)).isEqualTo(1);
    }
}
