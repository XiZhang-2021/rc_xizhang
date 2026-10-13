package xizhang.rc_xizhang_coding.job;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xizhang.rc_xizhang_coding.config.NotificationProperties;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.mapper.TaskMapper;
import xizhang.rc_xizhang_coding.service.DeliveryWorker;

@Component
public class CompensationJob {
    private final List<TaskMapper> mappers;
    private final DeliveryWorker worker;
    private final NotificationProperties properties;

    public CompensationJob(List<TaskMapper> mappers, DeliveryWorker worker, NotificationProperties properties) {
        this.mappers = mappers;
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${notification.scan-delay-ms:30000}",
               initialDelayString = "${notification.scan-initial-delay-ms:30000}")
    public void scan() {
        if (!properties.schedulerEnabled()) return;
        runOnce();
    }

    public void runOnce() {
        for (TaskMapper mapper : mappers) scanMapper(mapper);
    }

    private void scanMapper(TaskMapper mapper) {
        long maxId = mapper.maxId(); // Finite scan even if new tasks keep arriving.
        long lastId = 0;
        while (!Thread.currentThread().isInterrupted()) {
            var batch = mapper.findPending(lastId, maxId, properties.batchSize());
            if (batch.isEmpty()) return;
            for (NotificationTask task : batch) {
                if (Thread.currentThread().isInterrupted()) return;
                worker.deliver(mapper, task.getTaskId());
                lastId = task.getId();
            }
        }
    }
}
