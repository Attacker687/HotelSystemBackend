package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S12：非开发 profile（test）下 SQL 及其参数不输出，身份证号、密码哈希不进日志。 */
@ExtendWith(OutputCaptureExtension.class)
class SqlLogIT extends IntegrationTestBase {

    static final String MAPPER = "com.winniethepooh.hotelsystembackend.mapper";

    @Autowired
    private Environment env;

    /**
     * Logback 在整个测试 JVM 里共享：dev 上下文（DevProfileDemoDataIT、DevSqlLogIT）启动时把 mapper logger 调到 DEBUG，
     * 已缓存的 test 上下文不会重新初始化日志，是否受影响取决于测试类的执行顺序。
     * 这里按本上下文自己的配置重设该 logger，等同于单独以 test profile 启动时的状态，与执行顺序无关。
     */
    @BeforeEach
    void applyThisContextsMapperLogLevel() {
        String level = env.getProperty("logging.level." + MAPPER);
        LoggingSystem.get(getClass().getClassLoader())
                .setLogLevel(MAPPER, level == null ? null : LogLevel.valueOf(level.trim().toUpperCase()));
    }

    @Test
    void tc059_nonDevProfileDoesNotPrintSqlParameters(CapturedOutput output) {
        assertThat(env.getActiveProfiles()).containsExactly("test");

        Map<String, Object> n = Fixtures.registration("N");

        Resp r = post("/user/register", null, n);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        String hash = jdbc.queryForObject("select password from user where phone = ?", String.class, n.get("phone"));
        assertThat(output.getAll())
                .doesNotContain((String) n.get("idCardNumber"))
                .doesNotContain(hash)
                .doesNotContainPattern("==>\\s+Parameters:");
    }
}
