package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** TC-132～137：独立容器、真实每分钟 cron、浏览器界面。有限夹具桥接仅存在于测试 classpath。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserE2EIT {
    private static final Path OUTPUT = Path.of("target/e2e");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String FIXTURE_KEY = UUID.randomUUID().toString();
    private MySQLContainer<?> mysql;
    private GenericContainer<?> redis;
    private ServletWebServerApplicationContext app;
    private HttpServer bridge;
    private JdbcTemplate jdbc;
    private Fixtures fx;
    private Fixtures.Base base;

    private static MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.0").withEnv("TZ", "Asia/Shanghai")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCommand("--default-time-zone=+08:00", "--skip-log-bin", "--innodb-flush-log-at-trx-commit=0",
                        "--sync-binlog=0", "--innodb-doublewrite=0")
                .withUrlParam("serverTimezone", "Asia/Shanghai");
    }

    @BeforeAll
    void start() throws Exception {
        Files.createDirectories(OUTPUT);
        mysql = mysql().withInitScript("db/schema.sql");
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        mysql.start();
        redis.start();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(HotelSystemBackendApplication.class)
                .profiles("e2e").run("--server.port=0", "--spring.datasource.url=" + mysql.getJdbcUrl(),
                        "--spring.datasource.username=" + mysql.getUsername(), "--spring.datasource.password=" + mysql.getPassword(),
                        "--spring.data.redis.host=" + redis.getHost(), "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--hotel.jwt.secret=" + Fixtures.JWT_SECRET);
        assertThat(app.getEnvironment().getActiveProfiles()).containsExactly("e2e");
        assertThat(app.getEnvironment().getProperty("hotel.scheduler.enabled", Boolean.class)).isTrue();
        jdbc = app.getBean(JdbcTemplate.class);
        fx = new Fixtures(jdbc, app.getBean(StringRedisTemplate.class));
    }

    @BeforeEach
    void reset() throws Exception {
        app.getBean(FakeLlmClient.class).reset();
        fx.reset();
        base = fx.seedE2eBase();
        assertThat(jdbc.queryForObject("select count(*) from user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from staff", Integer.class)).isEqualTo(3);
        Files.write(OUTPUT.resolve("fixtures.json"), JSON.writeValueAsBytes(data()));
    }

    @AfterAll
    void stop() {
        if (bridge != null) bridge.stop(0);
        if (app != null) app.close();
        if (redis != null) redis.stop();
        if (mysql != null) mysql.stop();
    }

    private Map<String, Object> data() {
        Map<String, Object> registrations = new LinkedHashMap<>();
        for (String alias : List.of("E", "F", "H", "K")) registrations.put(alias, Fixtures.registration(alias));
        return Map.of("base", base, "registrations", registrations, "guest", Fixtures.guest("P0"),
                "newStaff", Fixtures.staffRegistration("G"), "today", LocalDate.now().toString(),
                "dates", List.of(LocalDate.now().plusDays(1).toString(), LocalDate.now().plusDays(2).toString(),
                        LocalDate.now().plusDays(3).toString(), LocalDate.now().plusDays(4).toString()));
    }

    private static Map<String, Object> state(JdbcTemplate db) {
        return Map.of("roomOrders", db.queryForList("select * from room_order order by id"),
                "mealOrders", db.queryForList("select * from meal_order order by id"),
                "rooms", db.queryForList("select * from room order by id"),
                "staff", db.queryForList("select id, account, role, status, is_deleted from staff order by id"),
                "tables", db.queryForList("select table_name from information_schema.tables where table_schema=database()", String.class));
    }

    private void bridge(JdbcTemplate db, Map<String, Object> testData) throws Exception {
        if (bridge != null) bridge.stop(0);
        bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/fixture", exchange -> {
            if (!exchange.getRequestMethod().equals("POST") || !FIXTURE_KEY.equals(exchange.getRequestHeaders().getFirst("X-Fixture-Key"))) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            int status = 200;
            byte[] body;
            try {
                JsonNode input = JSON.readTree(exchange.getRequestBody());
                String action = input.path("action").asText();
                long id = input.path("id").asLong();
                Object result = switch (action) {
                    case "data" -> testData;
                    case "state" -> state(db);
                    case "agent-inputs" -> app.getBean(FakeLlmClient.class).inputs();
                    case "agent-delayed-booking" -> {
                        var fake = app.getBean(FakeLlmClient.class);
                        fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, UUID.randomUUID().toString(), "propose_booking",
                                JSON.writeValueAsString(Map.of("roomNumber", base.room("R2").number(),
                                        "checkInDate", LocalDate.now().plusDays(1).toString(), "checkOutDate", LocalDate.now().plusDays(2).toString())), null, null));
                        fake.enqueueDelayed(Duration.ofSeconds(3), AgentItem.assistant("请核对卡片后点击确认。"));
                        yield Map.of("queued", true);
                    }
                    case "agent-expire" -> {
                        String actionId = UUID.fromString(input.path("actionId").asText()).toString();
                        var cache = app.getBean(StringRedisTemplate.class);
                        assertThat(cache.hasKey("agent:action:" + actionId)).isTrue();
                        yield Map.of("deleted", Boolean.TRUE.equals(cache.delete("agent:action:" + actionId)));
                    }
                    case "checkin", "checkout", "expire" -> {
                        assertThat(db.queryForObject("select count(*) from room_order where id=?", Integer.class, id)).isEqualTo(1);
                        int updated = switch (action) {
                            case "checkin" -> db.update("update room_order set checkin_time=? where id=?", LocalDateTime.now().minusMinutes(1), id);
                            case "checkout" -> db.update("update room_order set checkout_time=? where id=?", LocalDateTime.now().minusMinutes(1), id);
                            default -> db.update("update room_order set created_at=NOW() - INTERVAL 16 MINUTE where id=?", id);
                        };
                        yield Map.of("updated", updated);
                    }
                    default -> throw new IllegalArgumentException("未知夹具动作 " + action);
                };
                body = JSON.writeValueAsBytes(result);
            } catch (Throwable error) {
                status = 400;
                body = JSON.writeValueAsBytes(Map.of("error", error.toString()));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        bridge.start();
    }

    private void browser(String caseId, String url, JdbcTemplate db, Map<String, Object> testData) throws Exception {
        assertThat(Files.exists(Path.of("node_modules/@playwright/test/cli.js")))
                .as("先在项目根执行 npm ci，再执行 npx playwright install chromium").isTrue();
        bridge(db, testData);
        Path log = OUTPUT.resolve(caseId + ".log");
        var runner = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test", "--grep", caseId + " ")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        runner.environment().putAll(Map.of("E2E_BASE_URL", url,
                "E2E_FIXTURE_URL", "http://127.0.0.1:" + bridge.getAddress().getPort() + "/fixture",
                "E2E_FIXTURE_KEY", FIXTURE_KEY, "E2E_CASE", caseId, "E2E_REPORT", OUTPUT.resolve(caseId + ".xml").toString(),
                "FORCE_COLOR", "0"));
        runner.environment().remove("NO_COLOR");
        Process process = runner.start();
        try {
            assertThat(process.waitFor(360, TimeUnit.SECONDS)).as("浏览器超时：%s", log).isTrue();
            assertThat(process.exitValue()).as("%s 浏览器结果：\n%s", caseId, Files.readString(log, StandardCharsets.UTF_8)).isZero();
            String report = Files.readString(OUTPUT.resolve(caseId + ".xml"));
            assertThat(report).contains("tests=\"1\"", "failures=\"0\"", "errors=\"0\"", "skipped=\"0\"", caseId);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            bridge.stop(0);
            bridge = null;
        }
    }

    private void browser(String id) throws Exception {
        browser(id, "http://127.0.0.1:" + app.getWebServer().getPort(), jdbc, data());
    }

    @Test void tc051_agentQuoteSessionLifecycleAndMobileLayout() throws Exception { browser("TC-051"); }
    @Test void tc052_agentBookingIdempotencyAndCardBoundaries() throws Exception { browser("TC-052"); }
    @Test void tc053_agentPaymentRefundAndMealCards() throws Exception { browser("TC-053"); }
    @Test void tc054_agentOwnershipErrorsAndFragmentedStreams() throws Exception { browser("TC-054"); }

    @Test void tc132_guestRegistrationBookingPaymentRevenueAndCheckout() throws Exception { browser("TC-132"); }
    @Test void tc133_unpaidFrontOrderSurvivesTimeoutAndRoomIsCleaned() throws Exception { browser("TC-133"); }
    @Test void tc134_mealCancellationCompletionReviewAndTop10() throws Exception { browser("TC-134"); }
    @Test void tc135_disabledNewStaffSessionIsImmediatelyRejected() throws Exception { browser("TC-135"); }
    @Test void tc137_paidCancellationRefundAndRoomRebooking() throws Exception { browser("TC-137"); }

    @Test
    void tc136_emptyDatabaseDevBootDemoLoginAndSwagger() throws Exception {
        // TC-136 不复用 e2e 数据：从没有表的独立库执行两份正本脚本，再以环境变量启动 dev jar。
        try (var devMysql = mysql(); var devRedis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            devMysql.start();
            devRedis.start();
            var source = new DriverManagerDataSource(devMysql.getJdbcUrl(), devMysql.getUsername(), devMysql.getPassword());
            JdbcTemplate devJdbc = new JdbcTemplate(source);
            assertThat(devJdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database()", Integer.class)).isZero();
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/demo-data.sql"));
            }
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            Path log = OUTPUT.resolve("TC-136-dev.log");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var launcher = new ProcessBuilder(java, "-Duser.timezone=Asia/Shanghai", "-jar", "target/HotelSystemBackend-0.0.1-SNAPSHOT.jar")
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            launcher.environment().putAll(Map.of("SPRING_PROFILES_ACTIVE", "dev", "SERVER_PORT", Integer.toString(port),
                    "DB_URL", devMysql.getJdbcUrl(), "DB_USERNAME", devMysql.getUsername(), "DB_PASSWORD", devMysql.getPassword(),
                    "REDIS_HOST", devRedis.getHost(), "REDIS_PORT", devRedis.getMappedPort(6379).toString(),
                    "JWT_SECRET", Fixtures.JWT_SECRET));
            Process dev = launcher.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (dev.isAlive() && System.nanoTime() < deadline
                        && !Files.readString(log).contains("Started HotelSystemBackendApplication")) Thread.sleep(500);
                assertThat(Files.readString(log)).contains("Started HotelSystemBackendApplication");
                browser("TC-136", "http://127.0.0.1:" + port, devJdbc,
                        Map.of("demoManager", Map.of("login", "admin", "password", "Admin@123")));
            } finally {
                dev.destroy();
                if (!dev.waitFor(10, TimeUnit.SECONDS)) dev.destroyForcibly().waitFor();
            }
        }
    }
}
