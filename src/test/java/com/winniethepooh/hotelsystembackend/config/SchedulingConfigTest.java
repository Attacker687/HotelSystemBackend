package com.winniethepooh.hotelsystembackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/** hotel.scheduler.enabled：缺省时保持原行为（开启定时任务），false 时关闭。 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingEnabledByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ScheduledTaskHolder.class));
    }

    @Test
    void schedulingDisabledWhenPropertyFalse() {
        runner.withPropertyValues("hotel.scheduler.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScheduledTaskHolder.class));
    }
}
