package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionStoreTest {
    private static final String SESSION = "00000000-0000-0000-0000-000000000007";
    private static final String KEY = "agent:session:7:" + SESSION;
    private final ObjectMapper json = new ObjectMapper();
    private StringRedisTemplate redis;
    private ListOperations<String, String> lists;
    private SessionStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        store = new SessionStore(redis, json, new AgentProperties());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 19, 20, 21, 25})
    void tc037_latestTwentyUserTurnsAndPairedToolsAreKept(int turns) throws Exception {
        List<AgentItem> history = new ArrayList<>();
        for (int i = 1; i <= turns; i++) history.addAll(turn(i));
        when(lists.range(KEY, 0, -1)).thenReturn(encode(history));

        List<AgentItem> result = store.window(7, SESSION);

        int start = Math.max(0, turns - 20) * 5;
        assertThat(result).isEqualTo(history.subList(start, history.size()));
        assertThat(result.stream().filter(item -> item.type() == AgentItem.Type.USER).count()).isEqualTo(Math.min(turns, 20));
        verify(lists).range(KEY, 0, -1);
        if (turns > 20) verify(lists).trim(KEY, start, -1);
        else verify(lists, never()).trim(anyString(), anyLong(), anyLong());
        verifyNoMoreInteractions(lists);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @EnumSource(value = AgentItem.Type.class, names = {"FUNCTION_CALL", "FUNCTION_CALL_OUTPUT"})
    void tc037_orphanCallOrOutputIsDiscarded(AgentItem.Type type) throws Exception {
        List<AgentItem> valid = turn(1);
        List<AgentItem> history = new ArrayList<>(valid);
        history.add(new AgentItem(type, null, "orphan", "list_menu", "{}", "{\"ok\":true}", null));
        when(lists.range(KEY, 0, -1)).thenReturn(encode(history));

        assertThat(store.window(7, SESSION)).isEqualTo(valid);

        verify(lists, never()).trim(anyString(), anyLong(), anyLong());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void s02ac1_missingOrEmptyRedisListHasNoHistory(boolean emptyList) {
        when(lists.range(KEY, 0, -1)).thenReturn(emptyList ? List.of() : null);

        assertThat(store.window(7, SESSION)).isEmpty();

        verify(lists, never()).trim(anyString(), anyLong(), anyLong());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void s02ac2_allSixItemsAreWrittenInOneRpushThenExpireAndReadUnchanged() throws Exception {
        List<AgentItem> items = List.of(AgentItem.user("继续"),
                new AgentItem(AgentItem.Type.REASONING, null, null, null, null, null,
                        "{\"type\":\"reasoning\",\"encrypted_content\":\"opaque+/==\",\"summary\":[]}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "list_menu", "{ \"x\": 1 }", null,
                        "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"list_menu\",\"arguments\":\"{ \\\"x\\\": 1 }\"}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c1", null, null, "{ \"ok\": true }", null),
                new AgentItem(AgentItem.Type.ASSISTANT, "中文\n回复", null, null, null, null,
                        "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"中文\\n回复\"}]}"),
                new AgentItem(AgentItem.Type.NOTE, "[系统通知] 确认结果", null, null, null, null, null));
        ArgumentCaptor<Collection<String>> rows = ArgumentCaptor.forClass(Collection.class);

        store.append(7, SESSION, items);

        var commands = inOrder(lists, redis);
        commands.verify(lists).rightPushAll(eq(KEY), rows.capture());
        commands.verify(redis).expire(KEY, Duration.ofMinutes(30));
        verifyNoMoreInteractions(lists);
        assertThat(rows.getValue()).containsExactlyElementsOf(encode(items));
        when(lists.range(KEY, 0, -1)).thenReturn(new ArrayList<>(rows.getValue()));
        assertThat(store.window(7, SESSION)).isEqualTo(items);
        verify(redis, times(1)).expire(KEY, Duration.ofMinutes(30));
    }

    private List<String> encode(List<AgentItem> items) throws Exception {
        List<String> result = new ArrayList<>();
        for (AgentItem item : items) result.add(json.writeValueAsString(item));
        return result;
    }

    private List<AgentItem> turn(int i) {
        return List.of(AgentItem.user("u" + i),
                new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c" + i, "list_menu", "{}", null, null),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c" + i, null, null,
                        "{\"ok\":true,\"data\":{\"dishes\":[]}}", null),
                AgentItem.assistant("a" + i),
                new AgentItem(AgentItem.Type.NOTE, "[系统通知] n" + i, null, null, null, null, null));
    }
}
