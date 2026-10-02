package com.winniethepooh.hotelsystembackend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotCacheTest {
    private static final List<String> KEYS = List.of("room:detail:101", "price:0:2030-01-01", "room:detail:102", "price:0:2030-01-02");
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectMapper json;
    private HotCache cache;

    @BeforeEach void setup() {
        redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class); json = new ObjectMapper().findAndRegisterModules();
        cache = new HotCache(); ReflectionTestUtils.setField(cache, "redis", redis); ReflectionTestUtils.setField(cache, "json", json);
        ReflectionTestUtils.setField(cache, "enabled", true); when(redis.opsForValue()).thenReturn(values); clearInvocations(redis, values);
    }
    @AfterEach void clearTransaction() { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tc045_twoHundredValueTtlsAreRandomAndTwentyNullTtlsAreAlwaysThreeHundred(boolean empty) {
        List<Duration> ttls = new ArrayList<>(); List<String> stored = new ArrayList<>();
        doAnswer(inv -> { stored.add(inv.getArgument(1)); ttls.add(inv.getArgument(2)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        int count = empty ? 20 : 200;
        for (int i = 0; i < count; i++) assertThat(cache.get("room:detail:" + i, String.class, () -> empty ? null : "static")).isEqualTo(empty ? null : "static");
        assertThat(ttls).hasSize(count);
        if (empty) { assertThat(ttls).containsOnly(Duration.ofSeconds(300)); assertThat(stored).containsOnly("NULL"); }
        else { assertThat(ttls).allSatisfy(ttl -> assertThat(ttl.toSeconds()).isBetween(1800L, 2400L)); assertThat(ttls.stream().distinct().count()).isGreaterThanOrEqualTo(2); }
    }

    @ParameterizedTest @ValueSource(strings = {"get-read", "get-write", "all-read", "all-write", "delete-now", "delete-commit"})
    void tc047_sixRedisFaultsDoNotChangeLoaderResultsOrEscapeCallbacks(String fault) {
        RuntimeException failure = new IllegalStateException("injected"); AtomicInteger loads = new AtomicInteger();
        if (fault.equals("get-read")) when(values.get(any())).thenThrow(failure);
        if (fault.equals("all-read")) when(values.multiGet(anyCollection())).thenThrow(failure);
        if (fault.endsWith("write")) doThrow(failure).when(values).set(anyString(), anyString(), any(Duration.class));
        if (fault.startsWith("delete")) doThrow(failure).when(redis).delete(anyCollection());
        if (fault.startsWith("get")) {
            assertThat(cache.get(KEYS.get(0), String.class, () -> { loads.incrementAndGet(); return "database"; })).isEqualTo("database");
            assertThat(loads).hasValue(1);
        } else if (fault.startsWith("all")) {
            assertThat(cache.getAll(KEYS, String.class, missing -> { assertThat(missing).containsExactlyElementsOf(KEYS); loads.incrementAndGet(); return loaded(missing); })).isEqualTo(loaded(KEYS));
            assertThat(loads).hasValue(1); verify(values, times(1)).multiGet(KEYS);
        } else {
            if (fault.endsWith("commit")) TransactionSynchronizationManager.initSynchronization();
            assertThatCode(() -> cache.evictAfterCommit(KEYS)).doesNotThrowAnyException();
            if (fault.endsWith("commit")) assertThatCode(() -> synchronization().afterCommit()).doesNotThrowAnyException();
            verify(redis, times(1)).delete(KEYS);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"before", "commit", "rollback", "none"})
    void tc048_deleteOccursOnceAfterCommitNeverBeforeOrAfterRollbackAndImmediatelyWithoutTransaction(String timing) {
        List<String> callerKeys = new ArrayList<>(KEYS);
        if (!timing.equals("none")) TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit(callerKeys); callerKeys.clear();
        if (timing.equals("none")) { verify(redis, times(1)).delete(KEYS); return; }
        verify(redis, never()).delete(anyCollection());
        if (timing.equals("commit")) { synchronization().afterCommit(); verify(redis, times(1)).delete(KEYS); }
        else { if (timing.equals("rollback")) synchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK); verify(redis, never()).delete(anyCollection()); }
    }

    @ParameterizedTest @ValueSource(strings = {"get", "all", "delete"})
    void tc049_disabledReadsHaveZeroRedisInteractionButWritesStillEvict(String operation) {
        ReflectionTestUtils.setField(cache, "enabled", false); AtomicInteger loads = new AtomicInteger();
        if (operation.equals("get")) assertThat(cache.get(KEYS.get(0), String.class, () -> { loads.incrementAndGet(); return "database"; })).isEqualTo("database");
        else if (operation.equals("all")) assertThat(cache.getAll(KEYS, String.class, missing -> { assertThat(missing).containsExactlyElementsOf(KEYS); loads.incrementAndGet(); return loaded(missing); })).isEqualTo(loaded(KEYS));
        else { cache.evictAfterCommit(KEYS); verify(redis, times(1)).delete(KEYS); }
        if (!operation.equals("delete")) { assertThat(loads).hasValue(1); verifyNoInteractions(redis); }
        verifyNoInteractions(values);
    }

    @Test void tc047_mixedBatchReadsOnceLoadsOnlyMissingAndMalformedKeysAndCachesMissingValuesAsNull() throws Exception {
        when(values.multiGet(KEYS)).thenReturn(Arrays.asList(json.writeValueAsString("cached"), "NULL", null, "{bad"));
        AtomicInteger loads = new AtomicInteger();
        Map<String, String> result = cache.getAll(KEYS, String.class, missing -> {
            assertThat(missing).containsExactly(KEYS.get(2), KEYS.get(3)); loads.incrementAndGet(); return Map.of(KEYS.get(3), "fresh");
        });
        assertThat(result).containsEntry(KEYS.get(0), "cached").containsEntry(KEYS.get(1), null).containsEntry(KEYS.get(2), null).containsEntry(KEYS.get(3), "fresh");
        assertThat(loads).hasValue(1); verify(values, times(1)).multiGet(KEYS);
        verify(values).set(KEYS.get(2), "NULL", Duration.ofSeconds(300));
        verify(values).set(eq(KEYS.get(3)), eq("\"fresh\""), any(Duration.class));
        verify(values, never()).get(any()); verify(values, times(2)).set(anyString(), anyString(), any(Duration.class));
    }

    @ParameterizedTest @CsvSource({"get,false", "all,false", "get,true", "all,true"})
    void tc047_malformedJsonAndCheckedOrRuntimeJsonWritesStillReturnDatabaseValue(String operation, boolean checked) throws Exception {
        when(values.get(any())).thenReturn("{broken"); when(values.multiGet(anyCollection())).thenReturn(List.of("{broken", "{broken", "{broken", "{broken"));
        ObjectMapper broken = spy(json);
        if (checked) doThrow(JsonMappingException.fromUnexpectedIOE(new IOException("sensitive"))).when(broken).writeValueAsString(any());
        else doThrow(new IllegalStateException("sensitive")).when(broken).writeValueAsString(any());
        ReflectionTestUtils.setField(cache, "json", broken);
        if (operation.equals("get")) assertThat(cache.get(KEYS.get(0), String.class, () -> "database")).isEqualTo("database");
        else assertThat(cache.getAll(KEYS, String.class, HotCacheTest::loaded)).isEqualTo(loaded(KEYS));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @ParameterizedTest @CsvSource({"get,true", "get,false", "all,true", "all,false"})
    void tc047_loaderBusinessFailureIsNeverSwallowedOrWritten(String operation, boolean enabled) {
        ReflectionTestUtils.setField(cache, "enabled", enabled);
        BusinessException failure = new BusinessException(HttpStatus.NOT_FOUND, "database business failure"); AtomicInteger loads = new AtomicInteger();
        assertThatThrownBy(() -> {
            if (operation.equals("get")) cache.get(KEYS.get(0), String.class, () -> { loads.incrementAndGet(); throw failure; });
            else cache.getAll(KEYS, String.class, missing -> { loads.incrementAndGet(); throw failure; });
        }).isSameAs(failure);
        assertThat(loads).hasValue(1); verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        if (!enabled) verifyNoInteractions(redis, values);
    }

    @Test void tc047_warnContainsOnlyPrefixAndExceptionClassWithoutValuesMessagesOrStackTraces() {
        String sensitive = "入住人17000000001身份证110101";
        when(values.get(any())).thenReturn("{\"name\":\"" + sensitive);
        doThrow(new IllegalStateException(sensitive)).when(values).set(anyString(), anyString(), any(Duration.class));
        doThrow(new IllegalStateException(sensitive)).when(redis).delete(anyCollection());
        Logger logger = (Logger) LoggerFactory.getLogger(HotCache.class); ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            assertThat(cache.get("room:detail:" + sensitive, String.class, () -> sensitive)).isEqualTo(sensitive); cache.evictAfterCommit(KEYS);
            List<ILoggingEvent> warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warnings).hasSize(3).allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).matches("cache room: [A-Za-z]+Exception").doesNotContain(sensitive, "detail", "name");
                assertThat(e.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private TransactionSynchronization synchronization() { return TransactionSynchronizationManager.getSynchronizations().get(0); }
    private static Map<String, String> loaded(List<String> keys) { Map<String, String> result = new LinkedHashMap<>(); for (String key : keys) result.put(key, "database"); return result; }
}
