package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes the two production backfill statements against MySQL, without a test transaction. */
class RoomInventoryBackfillIT extends IntegrationTestBase {
    private final LocalDate today = LocalDate.now();
    private final LocalDate d = today.plusDays(10);

    @Test
    void tc029_backfillIncludesAllNightsOfFutureAndStartedOrdersOnly() throws Exception {
        long future = order("R1", d.plusDays(1), d.plusDays(4), 0, 0);
        long started = order("R2", today.minusDays(1), today.plusDays(2), 1, 0);
        long cancelled = order("R3", d.plusDays(1), d.plusDays(3), 0, 2);
        long deleted = order("R5", d.plusDays(1), d.plusDays(3), 0, 0);
        jdbc.update("update room_order set is_deleted=1 where id=?", deleted);
        long expired = order("R4", today.minusDays(5), today.minusDays(3), 1, 0);
        long done = order("S1", today.minusDays(4), today.minusDays(2), 1, 1);
        jdbc.update("delete from room_inventory");

        String[] steps = backfill();
        assertThat(jdbc.queryForList(steps[0])).isEmpty();
        jdbc.execute(steps[1]);

        assertThat(fx.count("room_inventory")).isEqualTo(6);
        assertThat(dates(future)).containsExactly(d.plusDays(1), d.plusDays(2), d.plusDays(3));
        assertThat(dates(started)).containsExactly(today.minusDays(1), today, today.plusDays(1));
        assertThat(fx.count("room_inventory", "order_id=? and room_id=?", future, base.room("R1").id())).isEqualTo(3);
        assertThat(fx.count("room_inventory", "order_id=? and room_id=?", started, base.room("R2").id())).isEqualTo(3);
        for (long excluded : List.of(cancelled, deleted, expired, done)) assertThat(dates(excluded)).isEmpty();
        assertInventoryConsistent();
    }

    @Test
    void tc030_backfillReportsOneSharedNightAndUniqueKeyFailureLeavesNoRows() throws Exception {
        long first = order("R1", d.plusDays(1), d.plusDays(3), 0, 0);
        long second = order("R1", d.plusDays(2), d.plusDays(4), 0, 0);
        jdbc.update("delete from room_inventory");

        String[] steps = backfill();
        assertThat(jdbc.queryForList(steps[0])).containsExactly(Map.of("room_id", base.room("R1").id(),
                "stay_date", Date.valueOf(d.plusDays(2)), "orders", 2L, "order_ids", first + "," + second));
        assertThatThrownBy(() -> jdbc.execute(steps[1])).isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("Duplicate entry");
        assertThat(fx.count("room_inventory")).isZero();
    }

    private long order(String room, LocalDate start, LocalDate end, int pay, int status) {
        long individual = jdbc.queryForObject("select id from individual where phone=?", Long.class, base.userA().login());
        return fx.roomOrder(base.userA().id(), individual, base.room(room).id(), start.atTime(14, 0),
                end.atTime(12, 0), new BigDecimal("597.00"), pay, status);
    }

    private List<LocalDate> dates(long order) {
        return jdbc.query("select stay_date from room_inventory where order_id=? order by stay_date",
                (rs, row) -> rs.getObject("stay_date", LocalDate.class), order);
    }

    private String[] backfill() throws Exception {
        String sql = new ClassPathResource("db/migration/m2-room-inventory-backfill.sql").getContentAsString(StandardCharsets.UTF_8);
        int split = sql.indexOf("-- 步骤 2");
        assertThat(split).isPositive();
        return new String[]{sql.substring(0, split), sql.substring(split)};
    }

    private void assertInventoryConsistent() {
        assertThat(jdbc.queryForList("""
                SELECT o.id FROM room_order o
                WHERE o.status = 0 AND o.is_deleted = 0 AND o.room_id IS NOT NULL AND o.checkout_time > NOW()
                  AND (SELECT COUNT(*) FROM room_inventory i WHERE i.order_id = o.id AND i.room_id = o.room_id
                         AND i.stay_date >= DATE(o.checkin_time) AND i.stay_date < DATE(o.checkout_time))
                      <> DATEDIFF(DATE(o.checkout_time), DATE(o.checkin_time))
                """)).as("I1: effective orders have all their nights").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT i.id FROM room_inventory i LEFT JOIN room_order o ON o.id = i.order_id
                WHERE o.id IS NULL OR o.is_deleted = 1 OR o.status = 2
                """)).as("I2: no orphan, deleted or cancelled occupancy").isEmpty();
    }
}
