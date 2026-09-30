package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S3 / GAP-03：GET /user/{id} 忽略路径参数只返回本人信息，身份证号保留前 6 位和后 4 位。 */
class UserInfoIT extends IntegrationTestBase {

    private Map<String, Object> userRow(int id) {
        return jdbc.queryForMap("select name, phone, email, id_card_number from user where id = ?", id);
    }

    @Test
    void tc018_userCannotReadAnotherUsersInfo() {
        String tokenA = login(base.userA());
        login(base.userB());
        int idB = base.userB().id();

        Resp r = get("/user/" + idB, tokenA);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        Map<String, Object> a = userRow(base.userA().id());
        assertThat(r.data().path("name").asText()).isEqualTo(a.get("name"));
        assertThat(r.data().path("phone").asText()).isEqualTo(a.get("phone"));
        String body = r.body().toString();
        Map<String, Object> b = userRow(idB);
        for (String col : new String[]{"name", "phone", "email", "id_card_number"}) {
            assertThat(body).as("响应不应包含 B 的 %s", col).doesNotContain((String) b.get(col));
        }
    }

    @Test
    void tc019_idCardNumberIsMasked() {
        int idA = base.userA().id();
        String full = (String) userRow(idA).get("id_card_number");

        Resp r = get("/user/" + idA, login(base.userA()));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String masked = r.data().path("idCardNumber").asText();
        assertThat(masked).isNotEqualTo(full).hasSize(18);
        assertThat(masked.substring(0, 6)).isEqualTo(full.substring(0, 6));
        assertThat(masked.substring(14)).isEqualTo(full.substring(14));
        assertThat(masked.substring(6, 14)).isEqualTo("********");
    }
}
