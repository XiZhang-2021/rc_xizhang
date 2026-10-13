package xizhang.rc_xizhang_coding.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import xizhang.rc_xizhang_coding.service.DeliveryWorker;

@Configuration
public class DeliveryConfiguration {
    @Bean
    DeliveryWorker.Sleeper sleeper() { return Thread::sleep; }

    @Bean
    ThreadPoolTaskExecutor deliveryExecutor(NotificationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("notification-delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
