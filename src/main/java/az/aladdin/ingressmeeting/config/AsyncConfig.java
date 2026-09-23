package az.aladdin.ingressmeeting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Single-threaded Meeting work queue so Whisper large-v3 never runs in parallel
 * (parallel jobs OOM-kill with exit 137 on ~16GB VMs).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String MEETING_EXECUTOR = "meetingTaskExecutor";

    @Bean(name = MEETING_EXECUTOR)
    public Executor meetingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("meeting-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
