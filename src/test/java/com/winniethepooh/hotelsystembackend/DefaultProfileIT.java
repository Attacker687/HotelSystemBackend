package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 / conventions §2：不设 SPRING_PROFILES_ACTIVE 时是生产配置，不激活 dev，不自动建表、不写演示数据。
 * 连共享 MySQL 容器里单独的空库 default_check 启动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DefaultProfileIT {

    private static final String DB = "default_check";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        var mysql = IntegrationTestBase.MYSQL;
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306);
        try (Connection root = DriverManager.getConnection(server + "/?useSSL=false&allowPublicKeyRetrieval=true",
                "root", mysql.getPassword())) {
            root.createStatement().execute("drop database if exists " + DB);
            root.createStatement().execute("create database " + DB + " default character set utf8mb4");
        }
        r.add("spring.datasource.url", () -> server + "/" + DB
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        r.add("spring.datasource.username", () -> "root");
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.data.redis.host", IntegrationTestBase.REDIS::getHost);
        r.add("spring.data.redis.port", () -> IntegrationTestBase.REDIS.getMappedPort(6379));
        r.add("hotel.scheduler.enabled", () -> "false");
    }

    @Autowired
    private Environment env;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void e1_defaultProfileIsProductionWithoutSchemaOrDemoData() {
        assertThat(env.getActiveProfiles()).isEmpty();
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(DB);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = ?",
                Integer.class, DB)).isZero();
    }
}
