package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.constant.StaffStatusConstant;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S8：新密码用 BCrypt 存储；旧的不加盐 MD5 账号登录成功后迁移为 BCrypt，改密码时也认旧哈希。 */
class PasswordHashingIT extends IntegrationTestBase {

    private static final String NEW_PASSWORD = "New@12345";

    private String userPassword(int id) {
        return jdbc.queryForObject("select password from user where id = ?", String.class, id);
    }

    private String staffPassword(int id) {
        return jdbc.queryForObject("select password from staff where id = ?", String.class, id);
    }

    /** 住客 C：库里仍是 S8 之前的 MD5 哈希。 */
    private Fixtures.Account legacyUser() {
        Fixtures.Account c = fx.user("测试住客丙", "17000000003", Fixtures.PASSWORD);
        jdbc.update("update user set password = ? where id = ?", Fixtures.md5(Fixtures.PASSWORD), c.id());
        return c;
    }

    @Test
    void tc039_registeredUserPasswordIsBcrypt() {
        String phone = "17000000900";
        Resp r = post("/user/register", null, Map.of("name", "测试新客", "idCardNumber", Fixtures.idCard(189),
                "phone", phone, "email", "n@example.test", "password", Fixtures.PASSWORD));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String stored = jdbc.queryForObject("select password from user where phone = ?", String.class, phone);
        assertThat(Fixtures.isBcryptOf(stored, Fixtures.PASSWORD)).as(stored).isTrue();
        assertThat(stored).isNotEqualTo(Fixtures.md5(Fixtures.PASSWORD));
    }

    @Test
    void tc040_staffCreatedByManagerHasBcryptPassword() {
        Resp r = post("/staff/register", login(base.manager()), Map.of("account", "it_bcrypt_staff",
                "password", Fixtures.PASSWORD, "role", RoleConstant.FRONT, "status", StaffStatusConstant.ACTIVE));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String stored = jdbc.queryForObject("select password from staff where account = ?", String.class, "it_bcrypt_staff");
        assertThat(Fixtures.isBcryptOf(stored, Fixtures.PASSWORD)).as(stored).isTrue();
        assertThat(stored).isNotEqualTo(Fixtures.md5(Fixtures.PASSWORD));
    }

    @Test
    void tc041_changedUserPasswordIsBcrypt() {
        Fixtures.Account a = base.userA();

        Resp r = post("/user/change", login(a), Map.of("phone", a.login(), "originPassword", a.password(),
                "passwordToChange", NEW_PASSWORD));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String stored = userPassword(a.id());
        assertThat(Fixtures.isBcryptOf(stored, NEW_PASSWORD)).as(stored).isTrue();
        assertThat(stored).isNotEqualTo(Fixtures.md5(NEW_PASSWORD));
    }

    @Test
    void tc042_legacyUserIsMigratedToBcryptOnSuccessfulLogin() {
        Fixtures.Account c = legacyUser();
        assertThat(userPassword(c.id())).matches("^[0-9a-f]{32}$");

        Resp r = post("/user/login", null, Map.of("phone", c.login(), "password", c.password()));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(r.data().path("token").asText()).isNotBlank();
        assertThat(Fixtures.isBcryptOf(userPassword(c.id()), c.password())).isTrue();
    }

    @Test
    void tc043_legacyStaffIsMigratedToBcryptOnSuccessfulLogin() {
        Fixtures.Account s = fx.staff("it_legacy_md5", Fixtures.PASSWORD, RoleConstant.FRONT, StaffStatusConstant.ACTIVE);
        jdbc.update("update staff set password = ? where id = ?", Fixtures.md5(Fixtures.PASSWORD), s.id());
        assertThat(staffPassword(s.id())).matches("^[0-9a-f]{32}$");

        Resp r = post("/staff/login", null, Map.of("account", s.login(), "password", s.password()));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(r.data().path("token").asText()).isNotBlank();
        assertThat(Fixtures.isBcryptOf(staffPassword(s.id()), s.password())).isTrue();
    }

    @Test
    void tc044_legacyUserIsNotMigratedOnWrongPassword() {
        Fixtures.Account c = legacyUser();
        String before = userPassword(c.id());

        Resp r = post("/user/login", null, Map.of("phone", c.login(), "password", "Wrong@1234"));

        assertThat(r.code()).isNotZero();
        assertThat(r.data().path("token").isMissingNode() || r.data().path("token").isNull()).isTrue();
        assertThat(userPassword(c.id())).isEqualTo(before);
    }

    @Test
    void tc045_userStillOnLegacyHashCanChangePassword() {
        Fixtures.Account c = legacyUser();
        String token = login(c);
        jdbc.update("update user set password = ? where id = ?", Fixtures.md5(c.password()), c.id());

        Resp r = post("/user/change", token, Map.of("phone", c.login(), "originPassword", c.password(),
                "passwordToChange", NEW_PASSWORD));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(Fixtures.isBcryptOf(userPassword(c.id()), NEW_PASSWORD)).isTrue();
    }
}
