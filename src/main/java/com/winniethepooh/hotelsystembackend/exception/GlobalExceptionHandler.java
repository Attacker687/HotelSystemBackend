package com.winniethepooh.hotelsystembackend.exception;

import com.winniethepooh.hotelsystembackend.entity.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * 统一错误响应：HTTP 状态表达错误类别，响应体仍是 Result（code=1，msg 为具体原因）。
 * <ul>
 *   <li>业务异常（BusinessException 及子类）：按异常自带的状态返回；</li>
 *   <li>Spring MVC 自身的异常（参数校验失败、请求体无法解析、方法不支持等）：沿用 Spring 的状态码，
 *       参数校验取第一条约束注解上的提示；</li>
 *   <li>其他未预期的异常：500，提示「操作失败，请联系管理员」，细节只进日志。</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getStatus()).body(Result.error(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleUnexpected(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.error("操作失败，请联系管理员"));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Result> handleLockConflict(PessimisticLockingFailureException e) {
        log.info("database lock conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error("系统繁忙，请稍后重试"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(status).headers(headers).body(Result.error(messageOf(ex, body)));
    }

    private static String messageOf(Exception ex, Object body) {
        List<? extends MessageSourceResolvable> errors = ex instanceof BindException b ? b.getAllErrors()
                : ex instanceof HandlerMethodValidationException v ? v.getAllErrors() : List.of();
        if (!errors.isEmpty()) return errors.get(0).getDefaultMessage();
        if (body instanceof ProblemDetail pd && pd.getDetail() != null) return pd.getDetail();
        return ex.getMessage();
    }
}
