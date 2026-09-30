package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1：以 dev profile 连一个空库启动，schema.sql 和演示数据自动加载，演示经理账号能登录；脚本可重复执行。
 * 用共享 MySQL 容器里单独的 demo_check 库，不影响其他集成测试的 test 库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevProfileDemoDataIT {

    private static final String DB = "demo_check";

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
    private JdbcTemplate jdbc;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void e1_devProfileLoadsSchemaAndDemoDataAndDemoManagerCanLogin() throws Exception {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(DB);
        int staff = count("staff"), rooms = count("room"), dishes = count("dish"), users = count("user");
        assertThat(staff).isGreaterThanOrEqualTo(3);
        assertThat(rooms).isPositive();
        assertThat(dishes).isPositive();
        assertThat(users).isPositive();

        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        Map<?, ?> body = rest.postForObject("/staff/login", Map.of("account", "admin", "password", "Admin@123"), Map.class);
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(((Map<?, ?>) body.get("data")).get("token")).asString().isNotBlank();

        // 重启 dev 应用会再次执行两份脚本：不报错、不产生重复数据
        try (Connection c = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/demo-data.sql"));
        }
        assertThat(count("staff")).isEqualTo(staff);
        assertThat(count("room")).isEqualTo(rooms);
        assertThat(count("dish")).isEqualTo(dishes);
        assertThat(count("user")).isEqualTo(users);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from `" + table + "`", Integer.class);
    }
}
