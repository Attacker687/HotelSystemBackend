package com.winniethepooh.hotelsystembackend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：hotel.scheduler.enabled（环境变量 HOTEL_SCHEDULER_ENABLED），默认 true。
 * 为 false 时 CustomTaskScheduler 仍是普通 bean，可以直接调用，只是不再按 cron 触发。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "hotel.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
