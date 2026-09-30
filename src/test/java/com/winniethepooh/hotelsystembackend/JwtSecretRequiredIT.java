package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** S11 / GAP-09：没有配置 JWT 密钥时拒绝启动。不继承 IntegrationTestBase，不注入 hotel.jwt.secret。 */
class JwtSecretRequiredIT {

    @Test
    void tc058_applicationRefusesToStartWithoutJwtSecret() {
        assertThat(System.getenv("JWT_SECRET")).isNull();
        assertThat(System.getProperty("hotel.jwt.secret")).isNull();
        var mysql = IntegrationTestBase.MYSQL;
        var redis = IntegrationTestBase.REDIS;
        String[] args = {
                "--server.port=0",
                "--spring.datasource.url=" + mysql.getJdbcUrl(),
                "--spring.datasource.username=" + mysql.getUsername(),
                "--spring.datasource.password=" + mysql.getPassword(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379)};

        ConfigurableApplicationContext ctx = null;
        Throwable failure = null;
        try {
            ctx = new SpringApplicationBuilder(HotelSystemBackendApplication.class).profiles("test").run(args);
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (ctx != null) ctx.close();
        }

        assertThat(ctx).as("上下文不应启动成功").isNull();
        assertThat(failure).isNotNull();
        assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                .containsAnyOf("hotel.jwt.secret", "JWT_SECRET");
    }
}
