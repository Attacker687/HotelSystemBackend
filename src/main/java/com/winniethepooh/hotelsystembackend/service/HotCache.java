package com.winniethepooh.hotelsystembackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/** Display data only; loader failures retain their ordinary business semantics. */
@Service
public class HotCache {
    private static final Logger log = LoggerFactory.getLogger(HotCache.class);
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper json;
    @Value("${hotel.cache.enabled:true}") private boolean enabled;

    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        if (!enabled) return loader.get();
        String stored = null;
        try { stored = redis.opsForValue().get(key); }
        catch (RuntimeException e) { warn(key, e); }
        if ("NULL".equals(stored)) { log.debug("cache {} hit", prefix(key)); return null; }
        T cached = stored == null ? null : decode(key, stored, type);
        if (cached != null) { log.debug("cache {} hit", prefix(key)); return cached; }
        log.debug("cache {} miss", prefix(key));
        T loaded = loader.get();
        write(key, loaded);
        return loaded;
    }

    public <T> Map<String, T> getAll(List<String> keys, Class<T> type, Function<List<String>, Map<String, T>> loader) {
        if (keys.isEmpty()) return Map.of();
        if (!enabled) return loader.apply(keys);
        List<String> stored = null;
        try { stored = redis.opsForValue().multiGet(keys); }
        catch (RuntimeException e) { warn(keys.get(0), e); }
        Map<String, T> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i), value = stored != null && i < stored.size() ? stored.get(i) : null;
            T cached = value == null || "NULL".equals(value) ? null : decode(key, value, type);
            if ("NULL".equals(value) || cached != null) {
                result.put(key, cached); log.debug("cache {} hit", prefix(key));
            } else { missing.add(key); log.debug("cache {} miss", prefix(key)); }
        }
        if (!missing.isEmpty()) {
            Map<String, T> loaded = loader.apply(missing);
            for (String key : missing) { T value = loaded.get(key); result.put(key, value); write(key, value); }
        }
        return result;
    }

    public void evictAfterCommit(Collection<String> keys) {
        if (keys.isEmpty()) return;
        List<String> copy = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(copy); }
            });
        } else evict(copy);
    }

    private <T> T decode(String key, String stored, Class<T> type) {
        try { return json.readValue(stored, type); }
        catch (JsonProcessingException | RuntimeException e) { warn(key, e); return null; }
    }
    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, value == null ? "NULL" : json.writeValueAsString(value),
                    Duration.ofSeconds(value == null ? 300 : ThreadLocalRandom.current().nextLong(1800, 2401)));
        } catch (JsonProcessingException | RuntimeException e) { warn(key, e); }
    }
    private void evict(List<String> keys) {
        try { redis.delete(keys); }
        catch (RuntimeException e) { warn(keys.get(0), e); }
    }
    private static String prefix(String key) { return key.substring(0, key.indexOf(':') + 1); }
    private static void warn(String key, Exception e) { log.warn("cache {} {}", prefix(key), e.getClass().getSimpleName()); }
}
