package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** W4 价格日历：B12 价格与房型校验，P3 批量写入。 */
class PriceCalendarIT extends IntegrationTestBase {

    private final LocalDate d = LocalDate.now().plusDays(10);
    private String manager;

    @BeforeEach
    void setUp() {
        manager = login(base.manager());
    }

    private Resp setPrice(Object roomType, String price, LocalDate start, LocalDate end) {
        return post("/business/calendar", manager, "{\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\",\"roomType\":" + roomType + ",\"price\":" + price + "}");
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("select * from price_calendar order by id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "0"})
    void tc101_nonPositivePriceRejectedWith400(String price) {
        fx.price(0, d.plusDays(1), new BigDecimal("300.00"));
        List<Map<String, Object>> before = snapshot();

        Resp r = setPrice(0, price, d, d);

        assertThat(r.status()).as("%s", r.body()).isEqualTo(400);
        assertThat(r.msg()).contains("价格");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void tc102_smallestPositivePriceIsWritten() {
        Resp r = setPrice(0, "0.01", d, d);

        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.code()).isZero();
        Map<String, Object> row = jdbc.queryForMap("select price, is_deleted from price_calendar where room_type = 0 and date = ?", d);
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("0.01");
        assertThat(((Number) row.get("is_deleted")).intValue()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, -1})
    void tc103_unknownRoomTypeRejectedWith400(int roomType) {
        int before = fx.count("price_calendar");

        Resp r = setPrice(roomType, "300", d, d);

        assertThat(r.status()).as("%s", r.body()).isEqualTo(400);
        assertThat(r.msg()).contains("房型");
        assertThat(fx.count("price_calendar")).isEqualTo(before);
    }

    @Test
    void tc121_batchPriceIsOneUpsertWithOneRowPerDate() {
        assertThat(setPrice(0, "350", d, d.plusDays(9)).code()).isZero();
        // 其中一天先被软删除：批量写入要把它恢复为有效记录，而不是再插一行
        jdbc.update("update price_calendar set is_deleted = 1 where room_type = 0 and date = ?", d.plusDays(3));

        sql.reset();
        Resp r = setPrice(0, "400", d, d.plusDays(29));
        List<String> writes = sql.statements().stream()
                .filter(s -> s.matches("(?is)^(insert|update)\\b.*price_calendar.*")).toList();

        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(writes).as("all=%s", sql.statements()).hasSize(1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select date, count(*) n, min(price) lo, max(price) hi from price_calendar " +
                        "where room_type = 0 and date between ? and ? and is_deleted = 0 group by date", d, d.plusDays(29));
        assertThat(rows).hasSize(30);
        assertThat(rows).allSatisfy(row -> {
            assertThat(((Number) row.get("n")).intValue()).isEqualTo(1);
            assertThat((BigDecimal) row.get("lo")).isEqualByComparingTo("400");
            assertThat((BigDecimal) row.get("hi")).isEqualByComparingTo("400");
        });
    }

    @Test
    void tc121_getCalendarReturnsOneEntryPerDateWithNullForUnsetDays() {
        fx.price(1, d, new BigDecimal("320.00"));
        fx.price(1, d.plusDays(2), new BigDecimal("330.00"));

        sql.reset();
        Resp r = get("/business/calendar?roomType=1&startDate=" + d + "&endDate=" + d.plusDays(2), manager);

        assertThat(r.code()).as("%s", r.body()).isZero();
        assertThat(r.data().size()).isEqualTo(3);
        assertThat(r.data().get(0).path("price").decimalValue()).isEqualByComparingTo("320.00");
        assertThat(r.data().get(1).isNull()).isTrue();
        assertThat(r.data().get(2).path("date").asText()).isEqualTo(d.plusDays(2).toString());
        assertThat(sql.count()).as("%s", sql.statements()).isEqualTo(1);
    }
}
