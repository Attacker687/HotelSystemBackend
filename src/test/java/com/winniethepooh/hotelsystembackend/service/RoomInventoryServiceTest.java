package com.winniethepooh.hotelsystembackend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomInventoryServiceTest {
    private final OrderMapper mapper = mock(OrderMapper.class);
    private final RoomMapper rooms = mock(RoomMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final OrderServiceImpl service = new OrderServiceImpl();
    private final LocalDate start = LocalDate.now().plusDays(11);

    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        ReflectionTestUtils.setField(service, "roomMapper", rooms);
        ReflectionTestUtils.setField(service, "userMapper", users);
        BaseContext.setCurrentId(7);
        Room room = new Room(); room.setId(101L); room.setRoomNumber("1101"); room.setRoomType(0);
        when(rooms.getRoomByRoomNumber("1101")).thenReturn(room); when(rooms.getPriceCalendars(anyInt(), any(), any())).thenReturn(List.of());
        Individual individual = new Individual(); individual.setId(9);
        when(users.findIndividual(any(), any(), any())).thenReturn(individual);
        doAnswer(inv -> { ((RoomOrder) inv.getArgument(0)).setId(55L); return null; }).when(mapper).insertRoomOrderV2(any());
    }
    @AfterEach void clear() { BaseContext.clear(); }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "deadlock", "timeout"})
    void tc031_inventoryFailuresBecomeBusiness409AndSafeInfo(String cause) {
        RuntimeException failure = switch (cause) {
            case "duplicate" -> new DuplicateKeyException("PII internal duplicate detail");
            case "deadlock" -> new DeadlockLoserDataAccessException("PII internal deadlock detail", new RuntimeException());
            default -> new CannotAcquireLockException("PII internal timeout detail");
        };
        doThrow(failure).when(mapper).insertRoomInventory(101L, start, 55L);
        Logger logger = (Logger) LoggerFactory.getLogger(OrderServiceImpl.class); ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> service.insertRoomOrderByUserService(dto(1))).isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo(cause.equals("duplicate") ? "房间在该时段已被预订" : "该时段预订繁忙，请稍后重试");
            });
            verify(mapper).insertRoomInventory(101L, start, 55L); verify(mapper, never()).insertRoomOrderNights(anyLong(), any());
            assertThat(logs.list).hasSize(1); ILoggingEvent event = logs.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.INFO); assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(dto(1).getName(), dto(1).getPhone(), dto(1).getIdCard(), "PII");
            assertThat(event.getArgumentArray()).containsExactly(55L, 101L);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @ParameterizedTest @CsvSource({"5,false", "30,false", "5,true"})
    void tc032_orderThenAscendingSingleNightInsertsThenNightPricesAndConflictStopsImmediately(int nights, boolean conflict) {
        if (conflict) doNothing().doNothing().doThrow(new DuplicateKeyException("third night conflict"))
                .when(mapper).insertRoomInventory(anyLong(), any(), anyLong());
        if (conflict) assertThatThrownBy(() -> service.insertRoomOrderByUserService(dto(nights))).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        else assertThat(service.insertRoomOrderByUserService(dto(nights))).isEqualTo(55L);
        int inserts = conflict ? 3 : nights; ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(mapper, times(inserts)).insertRoomInventory(eq(101L), dates.capture(), eq(55L));
        assertThat(dates.getAllValues()).containsExactlyElementsOf(start.datesUntil(start.plusDays(inserts)).toList());
        var ordered = inOrder(mapper); ordered.verify(mapper).insertRoomOrderV2(any());
        for (int i = 0; i < inserts; i++) ordered.verify(mapper).insertRoomInventory(101L, start.plusDays(i), 55L);
        if (conflict) verify(mapper, never()).insertRoomOrderNights(anyLong(), any());
        else ordered.verify(mapper).insertRoomOrderNights(eq(55L), any());
        ordered.verifyNoMoreInteractions();
    }

    private InsertRoomOrderDTO dto(int nights) {
        InsertRoomOrderDTO dto = new InsertRoomOrderDTO(); dto.setRoomNumber("1101"); dto.setCheckInTime(start.atTime(14, 0)); dto.setCheckOutTime(start.plusDays(nights).atTime(12, 0));
        dto.setName("测试入住人九"); dto.setPhone("17000000109"); dto.setIdCard("110101199001010170"); return dto;
    }
}
