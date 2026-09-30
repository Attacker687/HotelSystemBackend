package com.winniethepooh.hotelsystembackend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试基类：真实 HTTP（RANDOM_PORT）+ Testcontainers MySQL 8.0 / Redis 7。
 * <ul>
 *   <li>容器是静态单例，整个测试 JVM 只启动一次，所有子类和所有 Spring 上下文共用；由 Ryuk 在 JVM 退出时清理。</li>
 *   <li>MySQL 启动时执行 {@code db/schema.sql} 建表；数据库和 JVM 都是 Asia/Shanghai，NOW() 与 LocalDateTime.now() 一致。</li>
 *   <li>每个测试前清空全部业务表和 Redis，再写入 {@link Fixtures#seedBase()} 的基础数据，结果放在 {@link #base}。</li>
 *   <li>子类类名以 IT 结尾，由 failsafe 在 mvn verify 阶段运行。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SqlCounter.class)
public abstract class IntegrationTestBase {

    /** 时区全链路 Asia/Shanghai（conventions.md 第 1 节）：容器 TZ、会话时区 +08:00、JDBC serverTimezone；JVM 由 pom 的 argLine 设置。 */
    @ServiceConnection
    public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withEnv("TZ", "Asia/Shanghai")
            .withCommand("--default-time-zone=+08:00")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withInitScript("db/schema.sql");

    @ServiceConnection(name = "redis")
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    /** S11：JWT 密钥由测试进程运行时生成（Fixtures.JWT_SECRET），不写进任何配置文件。 */
    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("hotel.jwt.secret", () -> Fixtures.JWT_SECRET);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected SqlCounter sql;

    protected Fixtures fx;
    protected Fixtures.Base base;

    @BeforeEach
    void resetState() {
        // JDK HttpClient：4xx 响应（含 POST 的 401）也能正常读到状态码和响应体
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fx = new Fixtures(jdbc, redis);
        fx.reset();
        base = fx.seedBase();
        sql.reset();
    }

    // ---------- HTTP ----------

    /** 响应：HTTP 状态 + 解析后的 JSON（非 JSON 时 body 为 null）。 */
    public record Resp(int status, JsonNode body) {
        public int code() { return body.path("code").asInt(Integer.MIN_VALUE); }
        public String msg() { return body.path("msg").asText(null); }
        public JsonNode data() { return body.path("data"); }
    }

    /** token 可为 null；body 为 String 时按原始 JSON 发送，为 MultiValueMap 时按表单/multipart 发送，其余按 JSON 序列化。 */
    protected Resp call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.set("token", token);
        if (body instanceof String) headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        JsonNode json;
        try {
            json = r.getBody() == null ? null : JSON.readTree(r.getBody());
        } catch (Exception e) {
            json = null;
        }
        return new Resp(r.getStatusCode().value(), json);
    }

    protected Resp get(String path, String token) { return call(HttpMethod.GET, path, token, null); }
    protected Resp post(String path, String token, Object body) { return call(HttpMethod.POST, path, token, body); }
    protected Resp put(String path, String token, Object body) { return call(HttpMethod.PUT, path, token, body); }
    protected Resp delete(String path, String token) { return call(HttpMethod.DELETE, path, token, null); }

    // ---------- 登录 ----------

    /** 按账号角色走住客或员工登录接口，断言成功并返回 token。 */
    protected String login(Fixtures.Account a) {
        Resp r = a.role() == RoleConstant.USER
                ? post("/user/login", null, Map.of("phone", a.login(), "password", a.password()))
                : post("/staff/login", null, Map.of("account", a.login(), "password", a.password()));
        assertThat(r.code()).as("login %s: %s", a.login(), r.body()).isZero();
        return r.data().path("token").asText();
    }
}
