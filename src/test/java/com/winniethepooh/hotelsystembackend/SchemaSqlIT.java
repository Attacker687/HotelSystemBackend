package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1：schema.sql 在空库上建表。不启动应用；复用测试 JVM 共享的 MySQL 8.0 容器，
 * 在其中新建一个空库执行脚本（与单独起一个空容器等价，省一次容器启动）。
 */
class SchemaSqlIT {

    private static final String DB = "schema_check";

    @Test
    void tc131_schemaSqlCreatesAllTablesColumnsAndPriceCalendarUniqueKey() throws Exception {
        var mysql = IntegrationTestBase.MYSQL;
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306);
        try (Connection root = DriverManager.getConnection(server + "/?useSSL=false&allowPublicKeyRetrieval=true",
                "root", mysql.getPassword())) {
            root.createStatement().execute("drop database if exists " + DB);
            root.createStatement().execute("create database " + DB);
        }

        try (Connection c = DriverManager.getConnection(server + "/" + DB + "?useSSL=false&allowPublicKeyRetrieval=true",
                "root", mysql.getPassword())) {
            // 步骤 1：执行 schema.sql 不抛异常
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));

            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            List<String> tables = jdbc.queryForList(
                    "select table_name from information_schema.tables where table_schema = ?", String.class, DB);
            assertThat(tables).contains("user", "individual", "staff", "room", "price_calendar",
                    "room_order", "meal_order", "meal_order_item", "dish", "category");

            List<String> roomOrderCols = jdbc.queryForList(
                    "select column_name from information_schema.columns where table_schema = ? and table_name = 'room_order'",
                    String.class, DB);
            assertThat(roomOrderCols).contains("total_amount");

            // price_calendar 有 NON_UNIQUE=0 的索引，列恰为 (room_type, date)
            List<String> uniqueIndexCols = jdbc.queryForList(
                    "select group_concat(column_name order by seq_in_index) from information_schema.statistics " +
                            "where table_schema = ? and table_name = 'price_calendar' and non_unique = 0 and index_name <> 'PRIMARY' " +
                            "group by index_name", String.class, DB);
            assertThat(uniqueIndexCols).contains("room_type,date");
            bookingRequestSchema(jdbc);
        }
    }

    @Test
    void tc011_m1MigrationPreservesLegacyAssistantDefaultsAndGlobalRequestKey() throws Exception {
        var mysql = IntegrationTestBase.MYSQL;
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306);
        try (Connection root = DriverManager.getConnection(server + "/?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword())) {
            root.createStatement().execute("create database if not exists " + DB);
        }
        String url = server + "/" + DB + "?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(url, "root", mysql.getPassword())) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            jdbc.execute("drop table if exists booking_request");
            jdbc.execute("create table booking_request (id bigint not null auto_increment primary key, request_id varchar(64) not null, user_id int not null, action_type varchar(16) not null, order_id bigint null, status varchar(16) not null, created_at datetime not null default current_timestamp, updated_at datetime not null default current_timestamp, unique key uk_booking_request_request_id(request_id), key idx_booking_request_user(user_id)) engine=InnoDB default charset=utf8mb4");
            jdbc.update("insert into booking_request(request_id,user_id,action_type,status) values ('legacy-action-0001',7,'BOOKING','CANCELLED')");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/m1-booking-request.sql"));
            bookingRequestSchema(jdbc);
            var row = jdbc.queryForMap("select * from booking_request where request_id='legacy-action-0001'");
            assertThat(((Number) row.get("requester_role")).intValue()).isZero();
            assertThat(row).containsEntry("action_type", "BOOKING").containsEntry("status", "CANCELLED")
                    .containsEntry("request_hash", null).containsEntry("fail_status", null).containsEntry("fail_message", null);
        }
    }

    private void bookingRequestSchema(JdbcTemplate jdbc) {
        for (String[] spec : List.of(new String[]{"requester_role", "tinyint", "NO", "0"},
                new String[]{"request_hash", "char", "YES", null}, new String[]{"fail_status", "int", "YES", null},
                new String[]{"fail_message", "varchar", "YES", null})) {
            var column = jdbc.queryForMap("select data_type,is_nullable,column_default from information_schema.columns where table_schema=? and table_name='booking_request' and column_name=?", DB, spec[0]);
            assertThat(column).containsEntry("DATA_TYPE", spec[1]).containsEntry("IS_NULLABLE", spec[2]).containsEntry("COLUMN_DEFAULT", spec[3]);
        }
        assertThat(jdbc.queryForObject("select character_maximum_length from information_schema.columns where table_schema=? and table_name='booking_request' and column_name='request_hash'", Integer.class, DB)).isEqualTo(64);
        assertThat(jdbc.queryForObject("select character_maximum_length from information_schema.columns where table_schema=? and table_name='booking_request' and column_name='fail_message'", Integer.class, DB)).isEqualTo(255);
        assertThat(jdbc.queryForList("select column_name from information_schema.statistics where table_schema=? and table_name='booking_request' and index_name='idx_booking_request_created' order by seq_in_index", String.class, DB)).containsExactly("created_at");
        assertThat(jdbc.queryForList("select column_name from information_schema.statistics where table_schema=? and table_name='booking_request' and index_name='uk_booking_request_request_id' and non_unique=0 order by seq_in_index", String.class, DB)).containsExactly("request_id");
    }
}
