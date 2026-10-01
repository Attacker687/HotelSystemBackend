package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.agent.AgentService;
import com.winniethepooh.hotelsystembackend.agent.PendingActionService;
import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.entity.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/agent")
@RoleRequired({RoleConstant.USER})
public class AgentController {
    private final AgentService agentService;
    private final PendingActionService pending;

    public AgentController(AgentService agentService, PendingActionService pending) { this.agentService = agentService; this.pending = pending; }

    public record ChatRequest(
            @NotBlank(message = "会话不能为空")
            @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", message = "会话必须是UUID") String sessionId,
            @NotBlank(message = "消息不能为空") @Size(max = 500, message = "消息不能超过500字") String message) {}

    @PostMapping("/sessions")
    public Result sessions() { return Result.success(Map.of("sessionId", UUID.randomUUID().toString())); }

    @PostMapping("/chat")
    public void chat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        agentService.chat(BaseContext.getCurrentId(), request.sessionId(), request.message(), response);
    }

    @PostMapping("/actions/{id}/confirm")
    public Result confirm(@PathVariable String id) { return Result.success(pending.confirm(id, BaseContext.getCurrentId())); }

    @PostMapping("/actions/{id}/cancel")
    public Result cancel(@PathVariable String id) { return Result.success(pending.cancel(id, BaseContext.getCurrentId())); }
}
