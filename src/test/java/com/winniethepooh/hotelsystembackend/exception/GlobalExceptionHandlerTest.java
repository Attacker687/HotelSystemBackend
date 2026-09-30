package com.winniethepooh.hotelsystembackend.exception;

import com.winniethepooh.hotelsystembackend.entity.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** C1：兜底处理器返回 500 和通用提示；业务异常按自身状态返回原因。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void tc127_c1_unexpectedExceptionReturns500WithGenericMessage() {
        ResponseEntity<Result> r = handler.handleUnexpected(new NullPointerException("internal detail"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().getCode()).isEqualTo(1);
        assertThat(r.getBody().getMsg()).isEqualTo("操作失败，请联系管理员");
    }

    @Test
    void tc010_c1_forbiddenExceptionReturns403() {
        ResponseEntity<Result> r = handler.handleBusiness(new ForbiddenException("无权限访问该资源"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().getMsg()).isEqualTo("无权限访问该资源");
    }
}
