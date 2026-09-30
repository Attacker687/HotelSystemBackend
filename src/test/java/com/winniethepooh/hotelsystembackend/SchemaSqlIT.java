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
        }
    }
}
