package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.constant.StaffStatusConstant;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.RedisCallback;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** S6 / S7 / P1：登录态按 session:{ROLE}_{id} 反向索引撤销，员工 token 也有 3 小时 TTL，登录退出不再 SCAN。 */
class TokenSessionIT extends IntegrationTestBase {

    private static final String ROOMS = "/rooms?page=1&pageSize=10";
    private static final String NEW_PASSWORD = "New@12345";

    private Resp loginResp(Fixtures.Account a) {
        return a.role() == RoleConstant.USER
                ? post("/user/login", null, Map.of("phone", a.login(), "password", a.password()))
                : post("/staff/login", null, Map.of("account", a.login(), "password", a.password()));
    }

    private Map<String, Object> changePassword(Fixtures.Account a) {
        return Map.of("phone", a.login(), "originPassword", a.password(), "passwordToChange", NEW_PASSWORD);
    }

    @Test
    void tc032_staffReLoginInvalidatesPreviousToken() {
        String token1 = login(base.front());
        String token2 = login(base.front());

        assertThat(get(ROOMS, token1).status()).isEqualTo(401);
        Resp r = get(ROOMS, token2);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
    }

    @Test
    void tc033_deletedStaffTokenIsRevoked() {
        Fixtures.Account victim = base.staff("it_to_delete");
        String tokenX = login(victim);
        assertThat(get(ROOMS, tokenX).status()).isEqualTo(200);

        Resp del = delete("/staff?id=" + victim.id(), login(base.manager()));

        assertThat(del.code()).isZero();
        assertThat(jdbc.queryForObject("select is_deleted from staff where id = ?", Integer.class, victim.id())).isEqualTo(1);
        assertThat(get(ROOMS, tokenX).status()).isEqualTo(401);
    }

    @Test
    void tc033_disabledStaffTokenIsRevoked() {
        Fixtures.Account victim = base.staff("it_to_disable");
        String tokenX = login(victim);

        Resp r = post("/staff/status", login(base.manager()), Map.of("id", victim.id(), "status", StaffStatusConstant.INACTIVE));

        assertThat(r.code()).isZero();
        assertThat(get(ROOMS, tokenX).status()).isEqualTo(401);
    }

    @Test
    void tc034_userPasswordChangeRevokesOldToken() {
        String token1 = login(base.userA());

        Resp r = post("/user/change", token1, changePassword(base.userA()));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(get(ROOMS, token1).status()).isEqualTo(401);
    }

    @Test
    void tc035_userReLoginInvalidatesPreviousToken() {
        String token1 = login(base.userA());
        login(base.userA());

        assertThat(get(ROOMS, token1).status()).isEqualTo(401);
    }

    @Test
    void tc036_staffLogoutInvalidatesToken() {
        String token = login(base.front());

        Resp out = get("/staff/logout", token);

        assertThat(out.status()).isEqualTo(200);
        assertThat(out.code()).isZero();
        assertThat(get(ROOMS, token).status()).isEqualTo(401);
    }

    @Test
    void tc037_userLoginWritesSessionIndex() {
        Resp r = loginResp(base.userA());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String token = r.data().path("token").asText();
        assertThat(redis.opsForValue().get("session:USER_" + r.data().path("id").asInt())).isEqualTo(token);
    }

    @Test
    void tc038_staffLoginWritesSessionIndexAndTokenTtl() {
        Resp r = loginResp(base.front());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String token = r.data().path("token").asText();
        assertThat(redis.opsForValue().get("session:FRONT_" + r.data().path("id").asInt())).isEqualTo(token);
        assertThat(redis.getExpire(token)).isBetween(10740L, 10800L);
    }

    // ---------- TC-116：6 种操作都不触发 SCAN ----------

    private long scanCalls() {
        Properties info = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("commandstats"));
        String line = info == null ? null : info.getProperty("cmdstat_scan");
        if (line == null) return 0;
        for (String part : line.split(",")) {
            if (part.startsWith("calls=")) return Long.parseLong(part.substring("calls=".length()));
        }
        return 0;
    }

    private void writeNoise() {
        Map<String, String> noise = new HashMap<>();
        for (int i = 1; i <= 10000; i++) noise.put("noise:" + i, "v" + i);
        redis.opsForValue().multiSet(noise);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客登录", "员工登录", "员工退出", "住客改密码", "停用员工", "删除员工"})
    void tc116_loginLogoutAndRevocationDoNotScan(String op) {
        writeNoise();
        Supplier<Resp> action = switch (op) {
            case "住客登录" -> () -> loginResp(base.userA());
            case "员工登录" -> () -> loginResp(base.front());
            case "员工退出" -> {
                String t = login(base.front());
                yield () -> get("/staff/logout", t);
            }
            case "住客改密码" -> {
                String t = login(base.userA());
                yield () -> post("/user/change", t, changePassword(base.userA()));
            }
            case "停用员工" -> {
                Fixtures.Account s = base.staff("it_to_disable");
                login(s);
                String t = login(base.manager());
                yield () -> post("/staff/status", t, Map.of("id", s.id(), "status", StaffStatusConstant.INACTIVE));
            }
            case "删除员工" -> {
                Fixtures.Account s = base.staff("it_to_delete");
                login(s);
                String t = login(base.manager());
                yield () -> delete("/staff?id=" + s.id(), t);
            }
            default -> throw new IllegalArgumentException(op);
        };
        long before = scanCalls();

        Resp r = action.get();

        assertThat(r.code()).isZero();
        assertThat(scanCalls()).isEqualTo(before);
    }
}
