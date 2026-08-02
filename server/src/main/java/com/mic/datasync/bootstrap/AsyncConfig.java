package com.mic.datasync.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 统一异步执行器：运行启动、继续与重试都通过该线程池调度。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "syncTaskExecutor")
    public TaskExecutor syncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sync-run-");
        executor.initialize();
        return executor;
    }
}
