package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.BookingRequest;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderRequestServiceTest {
    private static final String KEY = "idem-unit-0001";
    @Mock private OrderMapper mapper;
    @Mock private OrderService orders;
    @Mock private TransactionTemplate tx;
    @InjectMocks private OrderRequestService service;
    private AutoCloseable mocks;
    private final AtomicBoolean insideTransaction = new AtomicBoolean();

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        BaseContext.setCurrentId(7); BaseContext.setCurrentRole(RoleConstant.USER);
        doAnswer(inv -> {
            insideTransaction.set(true);
            try { return ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)); }
            finally { insideTransaction.set(false); }
        }).when(tx).execute(any());
        when(orders.insertRoomOrderByUserService(any())).thenReturn(500L);
        when(orders.insertRoomOrderByFrontService(any())).thenReturn(500L);
        when(mapper.markBookingRequestSuccess(anyString(), anyLong())).thenReturn(1);
    }
    @AfterEach void clear() throws Exception { BaseContext.clear(); mocks.close(); }

    @ParameterizedTest
    @ValueSource(ints = {RoleConstant.USER, RoleConstant.FRONT})
    void tc005_paidIsPartOfDigestOnlyForFrontAndOrdinaryCanonicalMatchesDesign(int role) throws Exception {
        InsertRoomOrderDTO a = dto(), b = dto(); a.setPaid(true); b.setPaid(false);
        String first = capturedHash(a, role), second = capturedHash(b, role);
        if (role == RoleConstant.USER) assertThat(first).isEqualTo(second); else assertThat(first).isNotEqualTo(second);
        assertThat(second).isEqualTo(ordinaryHash(b, role));
    }

    @Test
    void tc005_parsedTimesAndNullEmptyStringsAreEquivalent() {
        InsertRoomOrderDTO a = dto(), b = dto();
        a.setCheckInTime(LocalDateTime.parse("2030-01-11T14:00")); b.setCheckInTime(LocalDateTime.parse("2030-01-11T14:00:00"));
        assertThat(capturedHash(a, RoleConstant.USER)).isEqualTo(capturedHash(b, RoleConstant.USER));
        a.setPhone(null); b.setPhone("");
        assertThat(capturedHash(a, RoleConstant.USER)).isEqualTo(capturedHash(b, RoleConstant.USER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"roomNumber", "checkInTime", "checkOutTime", "name", "phone", "idCard"})
    void tc005_eachOfTheSixBusinessFieldsChangesDigest(String field) {
        InsertRoomOrderDTO changed = dto();
        switch (field) {
            case "roomNumber" -> changed.setRoomNumber("1102");
            case "checkInTime" -> changed.setCheckInTime(changed.getCheckInTime().minusDays(1));
            case "checkOutTime" -> changed.setCheckOutTime(changed.getCheckOutTime().plusDays(1));
            case "name" -> changed.setName("测试入住人一");
            case "phone" -> changed.setPhone("17000000101");
            case "idCard" -> changed.setIdCard("110101199001010138");
        }
        assertThat(capturedHash(changed, RoleConstant.USER)).isNotEqualTo(capturedHash(dto(), RoleConstant.USER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\\"})
    void tc005_separatorAndBackslashCannotMoveContentBetweenFields(String prefix) {
        InsertRoomOrderDTO a = dto(), b = dto();
        a.setName("a" + prefix + "|b"); a.setPhone("c"); b.setName("a" + prefix); b.setPhone("b|c");
        // Both DTOs are accepted by the existing string fields but naïve joining collides.
        assertThat(a.getName() + "|" + a.getPhone()).isEqualTo(b.getName() + "|" + b.getPhone());
        assertThat(capturedHash(a, RoleConstant.USER)).isNotEqualTo(capturedHash(b, RoleConstant.USER));
    }

    @ParameterizedTest
    @CsvSource({"other,409,请求号已被占用，请更换后重试", "role,409,请求号已被占用，请更换后重试", "otherAndHash,409,请求号已被占用，请更换后重试",
            "assistant,422,请求号与内容不一致", "hash,422,请求号与内容不一致", "success,200,success", "failed400,400,离店日期必须晚于入住日期",
            "failed404,404,房间不存在", "processing,409,请求处理中，请稍后重试"})
    void tc014_existingRecordChecksIdentityBeforeTypeHashAndReplaysWithoutTransaction(String state, int status, String message) throws Exception {
        BookingRequest row = record(state.startsWith("failed") ? "FAILED" : state.equals("processing") ? "PROCESSING" : "SUCCESS");
        if (state.startsWith("other")) row.setUserId(8);
        if (state.equals("role")) row.setRequesterRole(RoleConstant.FRONT);
        if (state.equals("assistant")) row.setActionType("BOOKING");
        if (state.equals("hash") || state.equals("otherAndHash")) row.setRequestHash("b".repeat(64));
        if (state.startsWith("failed")) { row.setFailStatus(status); row.setFailMessage(message); }
        when(mapper.findBookingRequest(KEY)).thenReturn(row);
        if (status == 200) assertThat(service.placeOrder(KEY, dto())).isEqualTo(123L);
        else assertBusiness(() -> service.placeOrder(KEY, dto()), status, message);
        verify(mapper).findBookingRequest(KEY); verifyNoMoreInteractions(mapper); verifyNoInteractions(orders, tx);
    }

    @ParameterizedTest
    @CsvSource({"duplicate,SUCCESS", "duplicate,FAILED", "lock,FAILED", "lock,SUCCESS", "duplicate,MISSING", "lock,MISSING", "baseLock,PROCESSING"})
    void tc014_competitionRollsBackBeforeRereadAndReplaysCommittedWinner(String cause, String result) throws Exception {
        BookingRequest row = result.equals("MISSING") ? null : record(result);
        if (result.equals("FAILED")) { row.setFailStatus(404); row.setFailMessage("房间不存在"); }
        AtomicInteger reads = new AtomicInteger();
        when(mapper.findBookingRequest(KEY)).thenAnswer(inv -> { assertThat(insideTransaction).isFalse(); return reads.getAndIncrement() == 0 ? null : row; });
        RuntimeException conflict = cause.equals("duplicate") ? new DuplicateKeyException("duplicate") : cause.equals("lock") ? new CannotAcquireLockException("lock") : new PessimisticLockingFailureException("base lock");
        when(mapper.insertOrderRequest(eq(KEY), eq(7), eq(0), eq("PROCESSING"), anyString(), isNull(), isNull()))
                .thenAnswer(inv -> { assertThat(insideTransaction).isTrue(); throw conflict; });
        if (result.equals("SUCCESS")) assertThat(service.placeOrder(KEY, dto())).isEqualTo(123L);
        else assertBusiness(() -> service.placeOrder(KEY, dto()), result.equals("FAILED") ? 404 : 409, result.equals("FAILED") ? "房间不存在" : "请求处理中，请稍后重试");
        var ordered = inOrder(mapper, tx); ordered.verify(mapper).findBookingRequest(KEY); ordered.verify(tx).execute(any());
        ordered.verify(mapper).insertOrderRequest(eq(KEY), eq(7), eq(0), eq("PROCESSING"), anyString(), isNull(), isNull()); ordered.verify(mapper).findBookingRequest(KEY);
        verifyNoInteractions(orders); verify(mapper, never()).insertOrderRequest(any(), any(), any(), eq("FAILED"), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 500})
    void tc014_onlyDeterministicFailureIsStoredOutsideTheRolledBackTransaction(int status) {
        RuntimeException failure = status == 500 ? new RuntimeException("write failure") : new BusinessException(HttpStatus.valueOf(status), "original failure");
        when(orders.insertRoomOrderByUserService(any())).thenAnswer(inv -> { assertThat(insideTransaction).isTrue(); throw failure; });
        when(mapper.insertOrderRequest(eq(KEY), eq(7), eq(0), eq("FAILED"), anyString(), eq(status), eq("original failure")))
                .thenAnswer(inv -> { assertThat(insideTransaction).isFalse(); return 1; });
        assertThatThrownBy(() -> service.placeOrder(KEY, dto())).isSameAs(failure);
        verify(tx).execute(any());
        verify(mapper, times(status == 400 || status == 404 ? 1 : 0)).insertOrderRequest(eq(KEY), eq(7), eq(0), eq("FAILED"), anyString(), eq(status), eq("original failure"));
        verify(mapper, never()).markBookingRequestSuccess(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "other"})
    void tc014_failedRecordWriteFailureCannotReplaceOriginalBusinessFailure(String cause) {
        BusinessException original = new BusinessException(HttpStatus.BAD_REQUEST, "离店日期必须晚于入住日期");
        when(orders.insertRoomOrderByUserService(any())).thenThrow(original);
        when(mapper.insertOrderRequest(eq(KEY), eq(7), eq(0), eq("FAILED"), anyString(), eq(400), eq(original.getMessage())))
                .thenAnswer(inv -> { assertThat(insideTransaction).isFalse(); throw cause.equals("duplicate") ? new DuplicateKeyException("another writer won") : new RuntimeException("record write failed"); });
        assertThatThrownBy(() -> service.placeOrder(KEY, dto())).isSameAs(original);
        verify(mapper).insertOrderRequest(eq(KEY), eq(7), eq(0), eq("FAILED"), anyString(), eq(400), eq(original.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(ints = {RoleConstant.USER, RoleConstant.FRONT})
    void tc001_successClaimsProcessingAndMarksGeneratedIdWithinOneTransaction(int role) {
        BaseContext.setCurrentRole(role);
        when(mapper.insertOrderRequest(eq(KEY), eq(7), eq(role), eq("PROCESSING"), anyString(), isNull(), isNull())).thenAnswer(inv -> { assertThat(insideTransaction).isTrue(); return 1; });
        when(mapper.markBookingRequestSuccess(KEY, 500L)).thenAnswer(inv -> { assertThat(insideTransaction).isTrue(); return 1; });
        assertThat(service.placeOrder(KEY, dto())).isEqualTo(500L);
        var ordered = inOrder(mapper, orders); ordered.verify(mapper).findBookingRequest(KEY);
        ordered.verify(mapper).insertOrderRequest(eq(KEY), eq(7), eq(role), eq("PROCESSING"), anyString(), isNull(), isNull());
        if (role == RoleConstant.USER) ordered.verify(orders).insertRoomOrderByUserService(any()); else ordered.verify(orders).insertRoomOrderByFrontService(any());
        ordered.verify(mapper).markBookingRequestSuccess(KEY, 500L);
        assertThat(OrderRequestService.class.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void tc014_successUpdateMustChangeExactlyOneRowOrReturnConflict() {
        when(mapper.markBookingRequestSuccess(KEY, 500L)).thenReturn(0);
        assertBusiness(() -> service.placeOrder(KEY, dto()), 409, "请求处理中，请稍后重试");
        verify(mapper, never()).insertOrderRequest(any(), any(), any(), eq("FAILED"), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {RoleConstant.USER, RoleConstant.FRONT})
    void tc008_absentKeyDoesNotTouchMapperOrTransactionTemplate(int role) {
        BaseContext.setCurrentRole(role); assertThat(service.placeOrder(null, dto())).isEqualTo(500L); verifyNoInteractions(mapper, tx);
    }

    private String capturedHash(InsertRoomOrderDTO dto, int role) {
        BaseContext.setCurrentRole(role); clearInvocations(mapper, orders, tx); service.placeOrder(KEY, dto);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOrderRequest(eq(KEY), eq(7), eq(role), eq("PROCESSING"), hash.capture(), isNull(), isNull());
        assertThat(hash.getValue()).matches("[0-9a-f]{64}"); return hash.getValue();
    }
    private BookingRequest record(String state) throws Exception {
        BookingRequest row = new BookingRequest(); row.setRequestId(KEY); row.setUserId(7); row.setRequesterRole(0); row.setActionType("ORDER");
        row.setRequestHash(ordinaryHash(dto(), RoleConstant.USER)); row.setStatus(state); if (state.equals("SUCCESS")) row.setOrderId(123L); return row;
    }
    private String ordinaryHash(InsertRoomOrderDTO dto, int role) throws Exception {
        String canonical = dto.getRoomNumber() + "|" + dto.getCheckInTime() + "|" + dto.getCheckOutTime() + "|" + dto.getName() + "|" + dto.getPhone() + "|" + dto.getIdCard();
        if (role == RoleConstant.FRONT) canonical += "|" + dto.getPaid();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private InsertRoomOrderDTO dto() {
        InsertRoomOrderDTO dto = new InsertRoomOrderDTO(); dto.setRoomNumber("1101"); dto.setCheckInTime(LocalDateTime.parse("2030-01-11T14:00")); dto.setCheckOutTime(LocalDateTime.parse("2030-01-12T12:00"));
        dto.setName("测试入住人零"); dto.setPhone("17000000100"); dto.setIdCard("110101199001010111"); return dto;
    }
    private void assertBusiness(Runnable action, int status, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> { assertThat(e.getStatus().value()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(message); });
    }
}
