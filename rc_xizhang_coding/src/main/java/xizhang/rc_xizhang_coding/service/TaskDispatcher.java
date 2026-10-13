package xizhang.rc_xizhang_coding.service;

import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import xizhang.rc_xizhang_coding.mapper.TaskMapper;

@Component
public class TaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);
    private final ThreadPoolTaskExecutor executor;
    private final DeliveryWorker worker;

    public TaskDispatcher(ThreadPoolTaskExecutor deliveryExecutor, DeliveryWorker worker) {
        this.executor = deliveryExecutor;
        this.worker = worker;
    }

    public void enqueue(TaskMapper mapper, String taskId) {
        try { executor.execute(() -> worker.deliver(mapper, taskId)); }
        catch (RejectedExecutionException e) {
            log.info("Worker queue full; persisted task will be picked up by job: taskId={}", taskId);
        }
    }
}
