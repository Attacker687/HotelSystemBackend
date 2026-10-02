package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.BookingRequest;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PendingActionServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final String id = "12345678-1234-1234-1234-123456789abc", session = "87654321-4321-4321-4321-cba987654321";
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private OrderMapper mapper;
    private OrderService orders;
    private SessionStore sessions;
    private TransactionTemplate tx;
    private PendingActionService service;
    private final AtomicBoolean insideTransaction = new AtomicBoolean();

    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7); BaseContext.setCurrentRole(RoleConstant.USER);
        redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class); mapper = mock(OrderMapper.class);
        orders = mock(OrderService.class); sessions = mock(SessionStore.class); tx = mock(TransactionTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(inv -> {
            insideTransaction.set(true);
            try { return ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)); }
            finally { insideTransaction.set(false); }
        }).when(tx).execute(any());
        service = new PendingActionService(redis, json, new AgentProperties(), mapper, orders, tx, sessions);
    }
    @AfterEach void clear() { BaseContext.clear(); }

    @ParameterizedTest
    @CsvSource({"7,SUCCESS,ORDER", "7,FAILED,ORDER", "99,SUCCESS,ORDER", "7,SUCCESS,UNKNOWN", "99,SUCCESS,UNKNOWN", "7,SUCCESS,"})
    void tc012_nonAssistantRecordIsInvalidBeforeOwnershipAndDoesNotWrite(int owner, String state, String type) {
        BookingRequest row = record(owner, state); row.setActionType(type);
        when(mapper.findBookingRequest(id)).thenReturn(row);
        for (boolean confirm : List.of(true, false)) {
            assertThatThrownBy(() -> { if (confirm) service.confirm(id, 7); else service.cancel(id, 7); })
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getMessage()).isEqualTo("确认卡片已失效");
                    });
        }
        verify(mapper, times(2)).findBookingRequest(id); verifyNoMoreInteractions(mapper);
        verifyNoInteractions(orders, tx, sessions, redis, values);
    }

    @Test
    void s04ac1_createWritesExactOwnerParamsCardAndTtlInOneSet() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        StringRedisTemplate redis = mock(StringRedisTemplate.class); ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var service = new PendingActionService(redis, json, new AgentProperties(), mapper, orders, tx, sessions);
        var ctx = new AgentTools.ToolContext(7, UUID.randomUUID().toString());
        var params = Map.<String, Object>of("orderId", 123L);
        var lines = List.of(List.of("订单号", "123"));
        var action = service.create(ctx, PendingAction.Type.PAYMENT, params, new BigDecimal("199.00"), lines, List.of());
        assertThat(UUID.fromString(action.id()).toString()).isEqualTo(action.id());
        assertThat(action.userId()).isEqualTo(7); assertThat(action.sessionId()).isEqualTo(ctx.sessionId());
        assertThat(action.type()).isEqualTo(PendingAction.Type.PAYMENT); assertThat(action.params()).isEqualTo(params);
        assertThat(action.total()).isEqualByComparingTo("199.00");
        assertThat(action.card()).containsEntry("actionId", action.id()).containsEntry("type", "PAYMENT").containsEntry("title", "支付确认")
                .containsEntry("status", "PENDING").containsEntry("ttlSeconds", 600L).containsEntry("lines", lines).containsEntry("details", List.of()).containsEntry("total", "199.00");
        assertThat(LocalDateTime.parse(action.card().get("expiresAt").toString())).isBetween(LocalDateTime.now().plusMinutes(9), LocalDateTime.now().plusMinutes(11));
        var saved = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("agent:action:" + action.id()), saved.capture(), eq(Duration.ofMinutes(10)));
        assertThat(json.readTree(saved.getValue())).isEqualTo(json.readTree(json.writeValueAsString(action)));
        verifyNoMoreInteractions(values);
    }

    @ParameterizedTest
    @CsvSource({"confirm,otherRecord,403", "confirm,success,200", "confirm,cancelled,404", "confirm,missing,404", "confirm,otherKey,403",
            "cancel,otherRecord,403", "cancel,success,409", "cancel,cancelled,200", "cancel,missing,404", "cancel,otherKey,403"})
    void tc008_exactDecisionTableDoesNotCallAnyWriteOrDeleteOtherActions(String endpoint, String state, int status) throws Exception {
        if (state.equals("otherRecord")) when(mapper.findBookingRequest(id)).thenReturn(record(8, "SUCCESS"));
        if (state.equals("success")) when(mapper.findBookingRequest(id)).thenReturn(record(7, "SUCCESS"));
        if (state.equals("cancelled")) when(mapper.findBookingRequest(id)).thenReturn(record(7, "CANCELLED"));
        if (state.equals("otherKey")) when(values.get(key())).thenReturn(json.writeValueAsString(action(8, PendingAction.Type.BOOKING)));
        if (status == 200) {
            Map<String, Object> r = endpoint.equals("confirm") ? service.confirm(id, 7) : service.cancel(id, 7);
            assertThat(r).containsEntry("actionId", id).containsEntry("status", endpoint.equals("confirm") ? "CONFIRMED" : "CANCELLED");
            if (endpoint.equals("confirm")) assertThat(r).containsEntry("orderId", 123L);
        } else assertThatThrownBy(() -> { if (endpoint.equals("confirm")) service.confirm(id, 7); else service.cancel(id, 7); })
                .isInstanceOfSatisfying(BusinessException.class, e -> { assertThat(e.getStatus().value()).isEqualTo(status); assertThat(e.getMessage()).contains(status == 403 ? "无权限" : status == 404 ? "已失效" : "已确认"); });
        verify(mapper, never()).insertBookingRequest(any(), any(), any(), any()); verify(mapper, never()).markBookingRequestSuccess(any(), any());
        verifyNoInteractions(orders, tx, sessions); verify(redis, never()).delete(anyString());
        if (List.of("otherRecord", "success", "cancelled").contains(state)) verify(values, never()).get(anyString());
        if (state.equals("missing")) { verify(mapper, times(2)).findBookingRequest(id); verify(values).get(key()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "cancel"})
    void tc011_commitBetweenFirstReadAndRedisGetIsFoundBySecondRead(String endpoint) {
        when(mapper.findBookingRequest(id)).thenReturn(null, record(7, "SUCCESS"));
        if (endpoint.equals("confirm")) assertThat(service.confirm(id, 7)).containsEntry("status", "CONFIRMED").containsEntry("orderId", 123L);
        else assertThatThrownBy(() -> service.cancel(id, 7)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        var ordered = inOrder(mapper, values); ordered.verify(mapper).findBookingRequest(id); ordered.verify(values).get(key()); ordered.verify(mapper).findBookingRequest(id);
        verifyNoInteractions(orders, tx, sessions); verify(mapper, never()).insertBookingRequest(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateSuccess", "duplicateMissing", "lock", "lockBase", "business"})
    void tc057_conflictRereadOccursOutsideTransactionAndBusinessFailureKeepsOriginalException(String scenario) throws Exception {
        when(values.get(key())).thenReturn(json.writeValueAsString(action(7, PendingAction.Type.BOOKING)));
        AtomicInteger lookups = new AtomicInteger();
        when(mapper.findBookingRequest(id)).thenAnswer(inv -> { assertThat(insideTransaction).isFalse(); return lookups.getAndIncrement() > 0 && scenario.equals("duplicateSuccess") ? record(7, "SUCCESS") : null; });
        BusinessException failure = new BusinessException(HttpStatus.CONFLICT, "价格已变化");
        if (scenario.equals("business")) {
            when(mapper.insertBookingRequest(id, 7, "BOOKING", "PROCESSING")).thenReturn(1); when(orders.insertRoomOrderByUserService(any())).thenThrow(failure);
            assertThatThrownBy(() -> service.confirm(id, 7)).isSameAs(failure);
            verify(sessions).appendNote(eq(7), eq(session), contains("确认失败")); verify(mapper, times(1)).findBookingRequest(id);
        } else {
            RuntimeException conflict = scenario.startsWith("duplicate") ? new DuplicateKeyException("duplicate") : scenario.equals("lock") ? new CannotAcquireLockException("lock") : new PessimisticLockingFailureException("lock base");
            when(mapper.insertBookingRequest(id, 7, "BOOKING", "PROCESSING")).thenThrow(conflict);
            if (scenario.equals("duplicateSuccess")) assertThat(service.confirm(id, 7)).containsEntry("status", "CONFIRMED").containsEntry("orderId", 123L);
            else assertThatThrownBy(() -> service.confirm(id, 7)).isInstanceOfSatisfying(BusinessException.class, e -> { assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT); assertThat(e.getMessage()).contains("确认请求冲突"); });
            var ordered = inOrder(tx, mapper); ordered.verify(mapper).findBookingRequest(id); ordered.verify(tx).execute(any()); ordered.verify(mapper).insertBookingRequest(id, 7, "BOOKING", "PROCESSING"); ordered.verify(mapper).findBookingRequest(id);
            verifyNoInteractions(orders);
        }
        verify(mapper, never()).markBookingRequestSuccess(any(), any());
        if (!scenario.equals("duplicateSuccess")) verify(redis).delete(key()); else verify(redis, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateSuccess", "duplicateCancelled", "duplicateMissing", "lock", "lockBase", "normal", "deleteFailure"})
    void tc058_cancelUsesAutocommitUniqueInsertAndRedisFailureCannotChangeDurableResult(String scenario) throws Exception {
        when(values.get(key())).thenReturn(json.writeValueAsString(action(7, PendingAction.Type.BOOKING)));
        when(mapper.findBookingRequest(id)).thenReturn(null, scenario.equals("duplicateSuccess") ? record(7, "SUCCESS") : scenario.equals("duplicateCancelled") ? record(7, "CANCELLED") : null);
        if (scenario.startsWith("duplicate")) when(mapper.insertBookingRequest(id, 7, "BOOKING", "CANCELLED")).thenThrow(new DuplicateKeyException("duplicate"));
        else if (scenario.equals("lock") || scenario.equals("lockBase")) when(mapper.insertBookingRequest(id, 7, "BOOKING", "CANCELLED")).thenThrow(scenario.equals("lock") ? new CannotAcquireLockException("lock") : new PessimisticLockingFailureException("lock base"));
        else when(mapper.insertBookingRequest(id, 7, "BOOKING", "CANCELLED")).thenReturn(1);
        if (scenario.equals("deleteFailure")) when(redis.delete(key())).thenThrow(new RuntimeException("测试Redis故障"));
        if (List.of("duplicateSuccess", "duplicateMissing", "lock", "lockBase").contains(scenario))
            assertThatThrownBy(() -> service.cancel(id, 7)).isInstanceOfSatisfying(BusinessException.class, e -> { assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT); assertThat(e.getMessage()).contains(scenario.equals("duplicateSuccess") ? "已确认" : "操作冲突"); });
        else assertThat(service.cancel(id, 7)).containsExactlyInAnyOrderEntriesOf(Map.of("actionId", id, "status", "CANCELLED"));
        verify(mapper).insertBookingRequest(id, 7, "BOOKING", "CANCELLED"); verifyNoInteractions(tx, orders, sessions);
        if (scenario.equals("normal") || scenario.equals("deleteFailure")) verify(redis).delete(key());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "note", "both"})
    void s05ac5_independentPostCommitRedisFailuresPreserveConfirmedResult(String fault) throws Exception {
        when(values.get(key())).thenReturn(json.writeValueAsString(action(7, PendingAction.Type.BOOKING)));
        when(mapper.insertBookingRequest(id, 7, "BOOKING", "PROCESSING")).thenReturn(1); when(mapper.markBookingRequestSuccess(id, 123L)).thenReturn(1);
        when(orders.insertRoomOrderByUserService(any())).thenReturn(123L); when(mapper.getRoomOrderById(123L)).thenReturn(room(new BigDecimal("199.00")));
        doAnswer(inv -> { assertThat(insideTransaction).isFalse(); if (!fault.equals("note")) throw new RuntimeException("测试DEL故障"); return true; }).when(redis).delete(key());
        doAnswer(inv -> { assertThat(insideTransaction).isFalse(); if (!fault.equals("delete")) throw new RuntimeException("测试NOTE故障"); return null; }).when(sessions).appendNote(eq(7), eq(session), anyString());
        assertThat(service.confirm(id, 7)).containsEntry("actionId", id).containsEntry("type", "BOOKING").containsEntry("status", "CONFIRMED").containsEntry("orderId", 123L).containsEntry("message", "预订成功，订单号 123，请在 15 分钟内支付");
        var ordered = inOrder(tx, mapper, orders, redis, sessions); ordered.verify(mapper).findBookingRequest(id); ordered.verify(tx).execute(any()); ordered.verify(mapper).insertBookingRequest(id, 7, "BOOKING", "PROCESSING"); ordered.verify(orders).insertRoomOrderByUserService(any()); ordered.verify(mapper).getRoomOrderById(123L); ordered.verify(mapper).markBookingRequestSuccess(id, 123L); ordered.verify(redis).delete(key()); ordered.verify(sessions).appendNote(eq(7), eq(session), contains("123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOOKING", "PAYMENT", "CANCEL", "MEAL_ORDER"})
    void s05ac1_transactionFirstClaimsProcessingThenCallsExistingServiceAndMarksSuccess(String name) throws Exception {
        PendingAction.Type type = PendingAction.Type.valueOf(name); PendingAction action = action(7, type);
        when(values.get(key())).thenReturn(json.writeValueAsString(action)); when(mapper.insertBookingRequest(id, 7, name, "PROCESSING")).thenAnswer(inv -> { assertThat(insideTransaction).isTrue(); return 1; });
        when(mapper.markBookingRequestSuccess(id, 123L)).thenAnswer(inv -> { assertThat(insideTransaction).isTrue(); return 1; });
        when(orders.insertRoomOrderByUserService(any())).thenReturn(123L); when(mapper.getRoomOrderById(123L)).thenReturn(room(new BigDecimal("199.00"))); when(mapper.getRoomOrderByIdForUpdate(123L)).thenReturn(room(new BigDecimal("199.00")));
        doAnswer(inv -> { assertThat(insideTransaction).isTrue(); InsertMealOrderDTO dto = inv.getArgument(0); dto.setId(123); dto.setTotalAmount(new BigDecimal("199.00")); return null; }).when(orders).insertMealOrderService(any());
        Map<String, Object> r = service.confirm(id, 7); assertThat(r).containsEntry("orderId", 123L).containsEntry("status", "CONFIRMED");
        var ordered = inOrder(mapper, orders); ordered.verify(mapper).findBookingRequest(id); ordered.verify(mapper).insertBookingRequest(id, 7, name, "PROCESSING");
        switch (type) {
            case BOOKING -> {
                var dto = ArgumentCaptor.forClass(InsertRoomOrderDTO.class); ordered.verify(orders).insertRoomOrderByUserService(dto.capture()); ordered.verify(mapper).getRoomOrderById(123L);
                assertThat(dto.getValue().getName()).isEqualTo("单元测试住客"); assertThat(dto.getValue().getPhone()).isEqualTo("测试手机"); assertThat(dto.getValue().getIdCard()).isEqualTo("测试身份证"); assertThat(dto.getValue().getPaid()).isNull();
            }
            case PAYMENT -> { ordered.verify(mapper).getRoomOrderByIdForUpdate(123L); ordered.verify(orders).payRoomOrderService(123L); }
            case CANCEL -> { ordered.verify(orders).cancelRoomOrderService(123L); }
            case MEAL_ORDER -> {
                var dto = ArgumentCaptor.forClass(InsertMealOrderDTO.class); ordered.verify(orders).insertMealOrderService(dto.capture()); assertThat(dto.getValue().getItemList()).hasSize(1); assertThat(dto.getValue().getItemList().get(0).getDishId()).isEqualTo(1L); assertThat(dto.getValue().getItemList().get(0).getQuantity()).isEqualTo(2); assertThat(dto.getValue().getAddress()).isEqualTo("1101");
            }
        }
        ordered.verify(mapper).markBookingRequestSuccess(id, 123L);
    }

    private BookingRequest record(int user, String state) { BookingRequest r = new BookingRequest(); r.setUserId(user); r.setRequestId(id); r.setActionType("BOOKING"); r.setStatus(state); if (state.equals("SUCCESS")) r.setOrderId(123L); return r; }
    private RoomOrder room(BigDecimal total) { RoomOrder r = new RoomOrder(); r.setId(123L); r.setUserId(7); r.setTotalAmount(total); r.setPayStatus(0); return r; }
    private PendingAction action(int user, PendingAction.Type type) {
        Map<String, Object> params = switch (type) {
            case BOOKING -> Map.of("roomNumber", "1101", "checkIn", LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).toString(), "checkOut", LocalDateTime.now().plusDays(2).withHour(12).withMinute(0).toString(), "guestName", "单元测试住客", "guestPhone", "测试手机", "guestIdCard", "测试身份证");
            case PAYMENT, CANCEL -> Map.of("orderId", 123L);
            case MEAL_ORDER -> Map.of("items", List.of(Map.of("dishId", 1L, "quantity", 2)), "address", "1101", "remarks", "");
        };
        return new PendingAction(id, user, session, type, params, new BigDecimal("199.00"), Map.of("actionId", id, "type", type.name(), "status", "PENDING", "ttlSeconds", 600));
    }
    private String key() { return "agent:action:" + id; }
}
