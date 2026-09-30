package com.winniethepooh.hotelsystembackend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static com.winniethepooh.hotelsystembackend.SqlLogIT.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12：开发 profile 下 mapper 包的 SQL 以 DEBUG 级别输出。
 * dev 排在 test 之后，mapper 的 DEBUG 日志来自 application-dev.yml；显式关定时任务，用完即关闭上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "hotel.scheduler.enabled=false")
@ActiveProfiles({"test", "dev"})
@DirtiesContext
class DevSqlLogIT extends IntegrationTestBase {

    private final Logger mapperLogger = (Logger) LoggerFactory.getLogger(MAPPER);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Autowired
    private Environment env;

    @BeforeEach
    void attach() {
        appender.start();
        mapperLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        mapperLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void tc060_devProfileLogsMapperSqlAtDebug() {
        assertThat(env.getActiveProfiles()).contains("test", "dev");
        appender.list.clear();

        Resp r = post("/user/register", null, Fixtures.registration("N"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getLoggerName()).startsWith(MAPPER);
            assertThat(e.getFormattedMessage()).contains("Preparing:");
        });
    }
}
