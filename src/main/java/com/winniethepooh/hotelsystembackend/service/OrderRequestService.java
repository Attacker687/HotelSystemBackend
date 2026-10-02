package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.BookingRequest;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Service
@Slf4j
public class OrderRequestService {
    private final OrderMapper mapper;
    private final OrderService orders;
    private final TransactionTemplate tx;

    public OrderRequestService(OrderMapper mapper, OrderService orders, TransactionTemplate tx) {
        this.mapper = mapper; this.orders = orders; this.tx = tx;
    }

    public Long placeOrder(String key, InsertRoomOrderDTO dto) {
        if (key == null) return place(dto);
        if (!key.matches("[A-Za-z0-9_-]{8,64}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Idempotency-Key 必须是 8 到 64 位字母、数字、下划线或连字符");
        Integer uid = BaseContext.getCurrentId(), role = BaseContext.getCurrentRole();
        String hash = digest(dto, role);
        BookingRequest existing = mapper.findBookingRequest(key);
        if (existing != null) return replay(existing, uid, role, hash);
        try {
            return tx.execute(status -> {
                mapper.insertOrderRequest(key, uid, role, "PROCESSING", hash, null, null);
                Long id = place(dto);
                if (mapper.markBookingRequestSuccess(key, id) != 1) throw processing();
                return id;
            });
        } catch (DuplicateKeyException | PessimisticLockingFailureException e) {
            // The transaction has rolled back; reread the committed winner outside it.
            existing = mapper.findBookingRequest(key);
            if (existing != null) return replay(existing, uid, role, hash);
            throw processing();
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.BAD_REQUEST || e.getStatus() == HttpStatus.NOT_FOUND) {
                try { mapper.insertOrderRequest(key, uid, role, "FAILED", hash, e.getStatus().value(), e.getMessage()); }
                catch (RuntimeException failure) { log.warn("order failure record write failed key={} cause={}", key, failure.getClass().getSimpleName()); }
            }
            throw e;
        }
    }

    private Long place(InsertRoomOrderDTO dto) {
        return BaseContext.getCurrentRole() == RoleConstant.USER
                ? orders.insertRoomOrderByUserService(dto) : orders.insertRoomOrderByFrontService(dto);
    }

    private Long replay(BookingRequest record, Integer uid, Integer role, String hash) {
        if (!Objects.equals(record.getUserId(), uid) || !Objects.equals(record.getRequesterRole(), role))
            throw new BusinessException(HttpStatus.CONFLICT, "请求号已被占用，请更换后重试");
        if (!"ORDER".equals(record.getActionType()) || !Objects.equals(record.getRequestHash(), hash))
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "请求号与内容不一致");
        if ("SUCCESS".equals(record.getStatus()) || "FAILED".equals(record.getStatus()))
            log.info("order idempotency hit key={} status={}", record.getRequestId(), record.getStatus());
        if ("SUCCESS".equals(record.getStatus())) return record.getOrderId();
        if ("FAILED".equals(record.getStatus())) throw new BusinessException(HttpStatus.valueOf(record.getFailStatus()), record.getFailMessage());
        throw processing();
    }

    private static BusinessException processing() { return new BusinessException(HttpStatus.CONFLICT, "请求处理中，请稍后重试"); }

    private static String digest(InsertRoomOrderDTO dto, Integer role) {
        String canonical = String.join("|", field(dto.getRoomNumber()), field(dto.getCheckInTime()), field(dto.getCheckOutTime()),
                field(dto.getName()), field(dto.getPhone()), field(dto.getIdCard()));
        if (role == RoleConstant.FRONT) canonical += "|" + field(dto.getPaid());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(canonical)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException e) { throw new BusinessException(HttpStatus.BAD_REQUEST, "请求内容含非法 Unicode 字符"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    // Escape the escape character first, so delimiters inside existing DTO strings stay unambiguous.
    private static String field(Object value) { return Objects.toString(value, "").replace("\\", "\\\\").replace("|", "\\|"); }
}
