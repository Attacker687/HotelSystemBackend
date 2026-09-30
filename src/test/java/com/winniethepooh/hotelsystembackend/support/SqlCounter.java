package com.winniethepooh.hotelsystembackend.support;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * 统计经 MyBatis 实际发往数据库的 SQL 条数（拦截 StatementHandler.prepare，一条语句计一次，一级缓存命中不计）。
 * 全局计数，不区分线程：HTTP 请求在 Tomcat 线程上执行，测试 profile 已关闭定时任务，不会混入其他来源。
 * 用法：{@code sql.reset(); 调接口; assertThat(sql.count()).isLessThanOrEqualTo(n);}（基类每个测试前已 reset）。
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class SqlCounter implements Interceptor {

    private final List<String> statements = new ArrayList<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String text = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql();
        synchronized (statements) {
            statements.add(text.replaceAll("\\s+", " ").trim());
        }
        return invocation.proceed();
    }

    public void reset() {
        synchronized (statements) {
            statements.clear();
        }
    }

    public int count() {
        synchronized (statements) {
            return statements.size();
        }
    }

    /** 自上次 reset 以来执行过的 SQL（空白已压缩），便于断言失败时定位。 */
    public List<String> statements() {
        synchronized (statements) {
            return List.copyOf(statements);
        }
    }
}
