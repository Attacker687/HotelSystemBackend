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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionStoreTest {
    @Test
    void edgeF1_refusedCommitDoesNotExposePartialTurnOrLosePreviousHistory() {
        List<String> persisted = new ArrayList<>(List.of("old-user", "old-assistant"));
        doAnswer(i -> { persisted.addAll(i.getArgument(1)); return (long) persisted.size(); }).when(lists).rightPushAll(anyString(), any(java.util.Collection.class));
        when(redis.expire(anyString(), any(Duration.class))).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("refused"));
        when(redis.execute(any(RedisCallback.class))).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("refused"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.append(7, "session", List.of(AgentItem.user("failed"), AgentItem.assistant("failed")))).isInstanceOf(RuntimeException.class);
        assertThat(persisted).containsExactly("old-user", "old-assistant");
    }
    private static final String SESSION = "00000000-0000-0000-0000-000000000007";
    private static final String KEY = "agent:session:7:" + SESSION;
    private final ObjectMapper json = new ObjectMapper();
    private StringRedisTemplate redis;
    private ListOperations<String, String> lists;
    private SessionStore store;
    private RedisScriptingCommands scripts;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        RedisConnection connection = mock(RedisConnection.class); scripts = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripts);
        when(scripts.eval(any(byte[].class), eq(ReturnType.INTEGER), eq(1), any(byte[][].class))).thenReturn(1L);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(i -> ((RedisCallback<?>) i.getArgument(0)).doInRedis(connection));
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
    void s02ac2_allSixItemsAreWrittenInOneAtomicAppendAndReadUnchanged() throws Exception {
        List<AgentItem> items = List.of(AgentItem.user("继续"),
                new AgentItem(AgentItem.Type.REASONING, null, null, null, null, null,
                        "{\"type\":\"reasoning\",\"encrypted_content\":\"opaque+/==\",\"summary\":[]}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "list_menu", "{ \"x\": 1 }", null,
                        "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"list_menu\",\"arguments\":\"{ \\\"x\\\": 1 }\"}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c1", null, null, "{ \"ok\": true }", null),
                new AgentItem(AgentItem.Type.ASSISTANT, "中文\n回复", null, null, null, null,
                        "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"中文\\n回复\"}]}"),
                new AgentItem(AgentItem.Type.NOTE, "[系统通知] 确认结果", null, null, null, null, null));
        ArgumentCaptor<byte[][]> arguments = ArgumentCaptor.forClass(byte[][].class);

        store.append(7, SESSION, items);

        verify(redis).execute(any(RedisCallback.class));
        verify(scripts).eval(any(byte[].class), eq(ReturnType.INTEGER), eq(1), arguments.capture());
        verifyNoMoreInteractions(lists);
        List<String> args = java.util.Arrays.stream(arguments.getValue()).map(value -> new String(value, StandardCharsets.UTF_8)).toList();
        assertThat(args.get(0)).isEqualTo(KEY);
        assertThat(Long.parseLong(args.get(1))).isBetween(System.currentTimeMillis(), System.currentTimeMillis() + 60000);
        assertThat(args.get(2)).isEqualTo("1800000");
        List<String> rows = args.subList(3, args.size());
        assertThat(rows).containsExactlyElementsOf(encode(items));
        when(lists.range(KEY, 0, -1)).thenReturn(rows);
        assertThat(store.window(7, SESSION)).isEqualTo(items);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tc035_noteUsesSameAtomicAppendEntryAndRefreshesTtl() throws Exception {
        ArgumentCaptor<byte[][]> arguments = ArgumentCaptor.forClass(byte[][].class);
        String text = "[系统通知] 住客已确认：订单号 123";
        store.appendNote(7, SESSION, text);
        verify(redis).execute(any(RedisCallback.class));
        verify(scripts).eval(any(byte[].class), eq(ReturnType.INTEGER), eq(1), arguments.capture());
        List<String> args = java.util.Arrays.stream(arguments.getValue()).map(value -> new String(value, StandardCharsets.UTF_8)).toList();
        assertThat(args.get(0)).isEqualTo(KEY); assertThat(args.get(2)).isEqualTo("1800000");
        assertThat(args.subList(3, 4)).containsExactly(json.writeValueAsString(new AgentItem(AgentItem.Type.NOTE, text, null, null, null, null, null)));
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verifyNoMoreInteractions(lists);
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
