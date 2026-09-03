package com.videonest.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 为锁续期保留独立线程，避免长时间的业务定时任务饿死看门狗。 */
@Configuration
public class RedisLockConfiguration {

    @Bean(name = "redisLockWatchdogScheduler")
    public TaskScheduler redisLockWatchdogScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("videonest-redis-lock-watchdog-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
