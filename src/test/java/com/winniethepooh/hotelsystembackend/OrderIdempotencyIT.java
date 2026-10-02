package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.CustomTaskScheduler;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** M1: the real POST /order, agent endpoints and scheduled cleanup method. */
class OrderIdempotencyIT extends IntegrationTestBase {
    @SpyBean private OrderMapper mapper;
    @Autowired private CustomTaskScheduler scheduler;
    @Autowired private FakeLlmClient fake;
    @Autowired private ObjectMapper json;
    private final LocalDate d = LocalDate.now().plusDays(10);
    private static final String MISMATCH = "请求号与内容不一致";
    private static final String OCCUPIED = "请求号已被占用，请更换后重试";
    private static final String PROCESSING = "请求处理中，请稍后重试";

    @BeforeEach void prepare() { fake.reset(); }

    @ParameterizedTest
    @CsvSource({"uD800,existing", "uDC00,existing", "uD800,new", "uDC00,new", "chinese,valid", "emoji,valid"})
    void tc005_rawJsonUnicodeCannotAliasQuestionMarkAndValidNamesStillReplay(String value, String kind) throws Exception {
        String token = login(base.userA()), key = "idem-unicode-0001";
        Map<String, Object> body = body();
        if (kind.equals("valid")) {
            body.put("name", value.equals("chinese") ? "正常中文入住人" : "正常中文" + new String(Character.toChars(0x1F600)));
            String raw = json.writeValueAsString(body);
            Resp first = post("/order", token, raw, Map.of("Idempotency-Key", key)); ok(first);
            Map<String, List<Map<String, Object>>> before = snapshot();
            Resp repeated = post("/order", token, raw, Map.of("Idempotency-Key", key)); ok(repeated);
            assertThat(repeated.data()).isEqualTo(first.data()); assertThat(snapshot()).isEqualTo(before);
            body.put("name", body.get("name") + "改名");
            rejected(post("/order", token, json.writeValueAsString(body), Map.of("Idempotency-Key", key)), 422, MISMATCH);
            assertThat(snapshot()).isEqualTo(before);
        } else {
            if (kind.equals("existing")) { body.put("name", "?"); ok(book(token, key, body)); }
            Map<String, List<Map<String, Object>>> before = snapshot();
            body.put("name", "UNICODE_MARKER");
            String raw = json.writeValueAsString(body).replace("UNICODE_MARKER", "\\" + value);
            sql.reset(); Resp rejected = post("/order", token, raw, Map.of("Idempotency-Key", key));
            rejected(rejected, 400, "请求内容含非法 Unicode 字符");
            assertThat(snapshot()).isEqualTo(before); assertThat(sql.statements()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "front"})
    void tc001_sequentialRetriesReturnSameResultAndStoreOnlyOnePrivateRequest(String role) {
        boolean front = role.equals("front");
        var account = front ? base.front() : base.userA(); String token = login(account);
        String key = "idem-seq-0001-" + role; Map<String, Object> body = body();
        if (front) body.put("paid", false);
        JsonNode first = null;
        for (int i = 0; i < 3; i++) {
            Resp r = book(token, key, body); ok(r);
            if (i == 0) first = r.data(); else assertThat(r.data()).isEqualTo(first);
            if (front) assertThat(r.data().isNull()).isTrue(); else assertThat(r.data().asLong()).isPositive();
        }
        assertThat(fx.count("room_order")).isEqualTo(1); assertThat(fx.count("booking_request")).isEqualTo(1);
        Map<String, Object> order = jdbc.queryForMap("select * from room_order");
        Map<String, Object> request = request(key);
        assertThat(order.get("user_id")).isEqualTo(front ? null : account.id());
        assertThat(request).containsEntry("action_type", "ORDER").containsEntry("status", "SUCCESS")
                .containsEntry("order_id", order.get("id")).containsEntry("user_id", account.id());
        assertThat(((Number) request.get("requester_role")).intValue()).isEqualTo(account.role());
        assertThat((String) request.get("request_hash")).matches("[0-9a-f]{64}");
        assertThat(request.get("fail_status")).isNull(); assertThat(request.get("fail_message")).isNull();
        for (Object value : request.values()) if (value instanceof String text)
            assertThat(text).doesNotContain("测试入住人零", "17000000100", "110101199001010111");
        if (!front) assertThat(((Number) order.get("id")).longValue()).isEqualTo(first.asLong());
    }

    @Test
    void tc002_tenConcurrentRetriesCreateOneOrderAndNeverReturn500() throws Exception {
        String token = login(base.userA()), key = "idem-par-0001-user"; Map<String, Object> body = body();
        List<Callable<Resp>> jobs = new ArrayList<>(); for (int i = 0; i < 10; i++) jobs.add(() -> book(token, key, body));
        List<Resp> results = together(jobs);
        assertThat(fx.count("room_order")).isEqualTo(1); assertThat(fx.count("booking_request")).isEqualTo(1);
        long id = ((Number) request(key).get("order_id")).longValue();
        assertThat(results.stream().filter(r -> r.status() == 200).count()).isPositive();
        for (Resp r : results) {
            if (r.status() == 200) { ok(r); assertThat(r.data().asLong()).isEqualTo(id); }
            else rejected(r, 409, PROCESSING);
        }
        assertThat(request(key)).containsEntry("status", "SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherUser", "sameIdOtherRole", "otherAssistant"})
    void tc003_identityAndRoleWinOverContentAndDoNotExposeOriginalRequest(String scenario) {
        String key = "idem-owner-0001-user", token;
        if (scenario.equals("otherAssistant")) {
            fx.insert("booking_request", Map.of("request_id", key, "user_id", base.userA().id(), "action_type", "BOOKING", "status", "SUCCESS", "order_id", 123L));
            token = login(base.userB());
        } else if (scenario.equals("sameIdOtherRole")) {
            assertThat(base.userB().id()).isEqualTo(base.front().id());
            ok(book(login(base.userB()), key, body())); token = login(base.front());
        } else { ok(book(login(base.userA()), key, body())); token = login(base.userB()); }
        Map<String, List<Map<String, Object>>> before = snapshot();
        Map<String, Object> changed = body(); changed.put("roomNumber", "1102");
        Resp r = book(token, key, changed); rejected(r, 409, OCCUPIED);
        assertThat(r.data().isNull()).isTrue(); assertThat(r.body().toString()).doesNotContain("1101", "orderId");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"roomNumber", "checkInTime", "checkOutTime", "name", "phone", "idCard", "frontPaid", "failedCorrection", "ownAssistant"})
    void tc004_sameOwnerChangedContentOrAssistantKeyReturns422AndPreservesRows(String field) {
        String key = "idem-content-0001-user", token = login(field.equals("frontPaid") ? base.front() : base.userA());
        Map<String, Object> original = body(), changed = body();
        switch (field) {
            case "frontPaid" -> { original.put("paid", false); changed.put("paid", true); }
            case "failedCorrection" -> { original.put("checkInTime", Fixtures.iso(d.plusDays(2).atTime(14, 0))); original.put("checkOutTime", Fixtures.iso(d.plusDays(1).atTime(12, 0))); }
            case "ownAssistant" -> fx.insert("booking_request", Map.of("request_id", key, "user_id", base.userA().id(), "action_type", "BOOKING", "status", "CANCELLED"));
            case "roomNumber" -> changed.put(field, "1102");
            case "checkInTime" -> changed.put(field, Fixtures.iso(d.atTime(14, 0)));
            case "checkOutTime" -> changed.put(field, Fixtures.iso(d.plusDays(3).atTime(12, 0)));
            default -> changed.put(field, Fixtures.guest("P1").get(field));
        }
        if (field.equals("failedCorrection")) { Resp r = book(token, key, original); assertThat(r.status()).isEqualTo(400); }
        else if (!field.equals("ownAssistant")) ok(book(token, key, original));
        Map<String, List<Map<String, Object>>> before = snapshot();
        rejected(book(token, key, changed), 422, MISMATCH); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"400,离店", "404,房间不存在"})
    void tc006_deterministicFailureIsPersistedAfterRollbackAndReplayed(int status, String message) {
        String token = login(base.userA()), key = "idem-fail-0" + status + "-user"; Map<String, Object> body = body();
        if (status == 400) { body.put("checkInTime", Fixtures.iso(d.plusDays(2).atTime(14, 0))); body.put("checkOutTime", Fixtures.iso(d.plusDays(1).atTime(12, 0))); }
        else body.put("roomNumber", "9999");
        int individuals = fx.count("individual");
        Resp first = book(token, key, body), second = book(token, key, body);
        assertThat(first.status()).isEqualTo(status); assertThat(first.msg()).contains(message);
        rejected(second, status, first.msg()); assertThat(second.body()).isEqualTo(first.body());
        assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("room_order_night")).isZero(); assertThat(fx.count("individual")).isEqualTo(individuals);
        assertThat(fx.count("booking_request")).isEqualTo(1);
        assertThat(request(key)).containsEntry("status", "FAILED").containsEntry("action_type", "ORDER")
                .containsEntry("fail_status", status).containsEntry("fail_message", first.msg()).containsEntry("order_id", null)
                .containsEntry("user_id", base.userA().id());
        assertThat(((Number) request(key).get("requester_role")).intValue()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 500})
    void tc007_transientFailureLeavesNoRequestAndSameKeySucceedsAfterCauseIsRemoved(int status) {
        String token = login(base.userA()), key = "idem-retry-0" + status + "-user"; Map<String, Object> body = body();
        long conflictId = 0; String other = null;
        if (status == 409) { other = login(base.userB()); Resp booked = post("/order", other, body); ok(booked); conflictId = booked.data().asLong(); }
        else doThrow(new RuntimeException("injected order write failure")).when(mapper).insertRoomOrderV2(any());
        Map<String, List<Map<String, Object>>> before = snapshot();
        Resp failed = book(token, key, body);
        assertThat(failed.status()).isEqualTo(status); assertThat(failed.code()).isEqualTo(1);
        if (status == 409) assertThat(failed.msg()).contains("已被预订"); else assertThat(failed.msg()).isEqualTo("操作失败，请联系管理员");
        assertThat(snapshot()).isEqualTo(before); assertThat(fx.count("booking_request")).isZero();
        if (status == 409) ok(post("/order/cancel?id=" + conflictId, other, null)); else reset(mapper);
        Resp retried = book(token, key, body); ok(retried);
        assertThat(fx.count("room_order", "user_id=?", base.userA().id())).isEqualTo(1);
        assertThat(fx.count("room_order")).isEqualTo(status == 409 ? 2 : 1); assertThat(fx.count("booking_request")).isEqualTo(1);
        assertThat(request(key)).containsEntry("status", "SUCCESS").containsEntry("order_id", retried.data().asLong());
        if (status == 409) assertThat(jdbc.queryForObject("select status from room_order where id=?", Integer.class, conflictId)).isEqualTo(2);
    }

    @Test
    void tc008_withoutKeyKeepsOriginalResponsesAndExecutesNoIdempotencySql() {
        String user = login(base.userA()), front = login(base.front()); sql.reset();
        Resp first = post("/order", user, body()), second = post("/order", user, Fixtures.roomOrderBody("1102", d.plusDays(1).atTime(14, 0), d.plusDays(2).atTime(12, 0)));
        Map<String, Object> frontBody = Fixtures.roomOrderBody("1103", d.plusDays(1).atTime(14, 0), d.plusDays(2).atTime(12, 0)); frontBody.put("paid", false); frontBody.putAll(Fixtures.guest("P1"));
        Resp third = post("/order", front, frontBody); ok(first); ok(second); ok(third);
        assertThat(first.data().asLong()).isNotEqualTo(second.data().asLong()); assertThat(third.data().isNull()).isTrue();
        assertThat(fx.count("room_order")).isEqualTo(3); assertThat(fx.count("booking_request")).isZero();
        assertThat(sql.statements()).noneMatch(s -> s.toLowerCase().contains("booking_request"));
    }

    @ParameterizedTest
    @CsvSource({"7,false", "65,false", "empty,false", "blank,false", "space,false", "slash,false", "8,true", "64,true", "symbols,true"})
    void tc009_headerCharacterAndLengthBoundariesAreValidatedBeforeWrites(String value, boolean valid) {
        String key = switch (value) { case "empty" -> ""; case "blank" -> "        "; case "space" -> "abcd efgh"; case "slash" -> "abcd/efgh"; case "symbols" -> "ab_cd-ef_gh-12"; default -> "k".repeat(Integer.parseInt(value)); };
        Resp r = book(login(base.userA()), key, body());
        if (valid) { ok(r); assertThat(fx.count("room_order")).isEqualTo(1); assertThat(request(key)).containsEntry("status", "SUCCESS"); }
        else { rejected(r, 400, "Idempotency-Key 必须是 8 到 64 位字母、数字、下划线或连字符"); assertThat(fx.count("room_order")).isZero(); }
        assertThat(fx.count("booking_request")).isEqualTo(valid ? 1 : 0);
    }

    @Test
    void tc010_webKeyIsInvalidForBothAgentEndpointsForOwnerAndOtherUser() {
        String owner = login(base.userA()), other = login(base.userB()), key = "idem-actid-0001-user";
        ok(book(owner, key, body())); Map<String, List<Map<String, Object>>> before = snapshot();
        for (String token : List.of(owner, other)) for (String endpoint : List.of("confirm", "cancel"))
            rejected(post("/agent/actions/" + key + "/" + endpoint, token, null), 404, "确认卡片已失效");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void tc011_assistantConfirmAndCancelUseLegacyInsertWithNullableNewColumns() throws Exception {
        String token = login(base.userA()); String confirmed = proposeBooking(token, "1101"), cancelled = proposeBooking(token, "1102");
        Resp first = post("/agent/actions/" + confirmed + "/confirm", token, null), second = post("/agent/actions/" + cancelled + "/cancel", token, null);
        ok(first); ok(second); assertThat(first.data().path("status").asText()).isEqualTo("CONFIRMED"); assertThat(second.data().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(fx.count("booking_request")).isEqualTo(2);
        for (String key : List.of(confirmed, cancelled)) {
            Map<String, Object> request = request(key);
            assertThat(request).containsEntry("user_id", base.userA().id()).containsEntry("action_type", "BOOKING")
                    .containsEntry("request_hash", null).containsEntry("fail_status", null).containsEntry("fail_message", null);
            assertThat(((Number) request.get("requester_role")).intValue()).isZero();
        }
        assertThat(request(confirmed)).containsEntry("status", "SUCCESS").containsEntry("order_id", first.data().path("orderId").asLong());
        assertThat(request(cancelled)).containsEntry("status", "CANCELLED").containsEntry("order_id", null);
    }

    @Test
    void tc013_cleanupDeletesOnlyRecordsOlderThanSevenDaysAndIsRepeatable() throws Exception {
        for (String age : List.of("old", "new")) for (String kind : List.of("order", "agent")) {
            Map<String, Object> row = new LinkedHashMap<>(Map.of("request_id", age + "-" + kind + "-0001", "user_id", base.userA().id(), "action_type", kind.equals("order") ? "ORDER" : "BOOKING", "status", "SUCCESS"));
            if (kind.equals("order")) row.put("request_hash", "a".repeat(64)); fx.insert("booking_request", row);
        }
        jdbc.update("update booking_request set created_at=NOW() - INTERVAL 169 HOUR where request_id like 'old-%'");
        jdbc.update("update booking_request set created_at=NOW() - INTERVAL 167 HOUR where request_id like 'new-%'");
        var method = CustomTaskScheduler.class.getMethod("cleanBookingRequests");
        assertThat(method.getAnnotation(Scheduled.class).cron()).isEqualTo("0 30 3 * * ?");
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        sql.reset(); scheduler.cleanBookingRequests();
        assertThat(sql.statements()).hasSize(1); assertThat(sql.statements().get(0).toLowerCase())
                .isEqualTo("delete from booking_request where created_at < date_sub(now(), interval 7 day)");
        assertThat(jdbc.queryForList("select request_id from booking_request order by request_id", String.class)).containsExactly("new-agent-0001", "new-order-0001");
        scheduler.cleanBookingRequests(); assertThat(fx.count("booking_request")).isEqualTo(2);
        assertThat(fx.count("scheduler_task_lock")).isZero();
    }

    private Map<String, Object> body() { return Fixtures.roomOrderBody("1101", d.plusDays(1).atTime(14, 0), d.plusDays(2).atTime(12, 0)); }
    private Resp book(String token, String key, Map<String, Object> body) { return post("/order", token, body, Map.of("Idempotency-Key", key)); }
    private Map<String, Object> request(String key) { return jdbc.queryForMap("select * from booking_request where request_id=?", key); }
    private void ok(Resp r) { assertThat(r.status()).as("%s", r.body()).isEqualTo(200); assertThat(r.code()).isZero(); }
    private void rejected(Resp r, int status, String message) { assertThat(r.status()).as("%s", r.body()).isEqualTo(status); assertThat(r.code()).isEqualTo(1); assertThat(r.msg()).isEqualTo(message); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("room_order", "room_order_night", "individual", "booking_request")) rows.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        return rows;
    }
    private String proposeBooking(String token, String room) throws Exception {
        Resp session = post("/agent/sessions", token, null); ok(session);
        fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "propose_booking", json.writeValueAsString(Map.of("roomNumber", room, "checkInDate", d.plusDays(1).toString(), "checkOutDate", d.plusDays(2).toString())), null, null));
        fake.enqueue(AgentItem.assistant("测试收尾"));
        HttpHeaders headers = new HttpHeaders(); headers.set("token", token); headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange("/agent/chat", HttpMethod.POST, new HttpEntity<>(Map.of("sessionId", session.data().path("sessionId").asText(), "message", "执行测试"), headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        for (String event : response.getBody().split("\n\n")) if (event.startsWith("event: card\n"))
            return json.readTree(event.substring("event: card\ndata: ".length())).path("actionId").asText();
        throw new AssertionError("missing proposal card: " + response.getBody());
    }
    private <T> List<T> together(List<Callable<T>> jobs) throws Exception {
        var pool = Executors.newFixedThreadPool(jobs.size()); CountDownLatch ready = new CountDownLatch(jobs.size()), go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(); for (Callable<T> job : jobs) futures.add(pool.submit(() -> { ready.countDown(); go.await(); return job.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown(); List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(60, TimeUnit.SECONDS)); return results;
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
