package xizhang.rc_xizhang_coding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.client.SupplierClient;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;
import xizhang.rc_xizhang_coding.lock.MysqlTaskLock;
import xizhang.rc_xizhang_coding.mapper.TaskMapper;
import xizhang.rc_xizhang_coding.validation.RequestPolicy;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final MysqlTaskLock locks;
    private final SupplierClient client;
    private final JsonMapper json;
    private final RequestPolicy policy;
    private final Sleeper sleeper;

    @FunctionalInterface
    public interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }

    public DeliveryWorker(MysqlTaskLock locks, SupplierClient client,
                          JsonMapper json, RequestPolicy policy, Sleeper sleeper) {
        this.locks = locks;
        this.client = client;
        this.json = json;
        this.policy = policy;
        this.sleeper = sleeper;
    }

    public void deliver(TaskMapper mapper, String taskId) {
        try (var lease = locks.tryAcquire(taskId)) {
            if (lease == null) return;
            for (int attempt = 0; attempt < 4; attempt++) {
                lease.checkOwned();
                NotificationTask task = mapper.findByTaskId(taskId);
                if (task == null || !task.pending()) return;
                RequestData data = json.readValue(task.getRequestData(), RequestData.class);
                policy.checkTarget(data);
                SupplierClient.Outcome result = client.send(task, data);
                lease.checkOwned();
                switch (result) {
                    case SUCCESS -> {
                        mapper.finishDelivery(taskId, true, DeliveryStatus.SUCCESS);
                        return;
                    }
                    case DUPLICATE -> {
                        mapper.finishDelivery(taskId, false, DeliveryStatus.DUPLICATE);
                        return;
                    }
                    case FAILED -> {
                        mapper.finishDelivery(taskId, false, DeliveryStatus.FAILED);
                        log.warn("Supplier rejected task or returned invalid protocol: taskId={}", taskId);
                        return;
                    }
                    case RETRY -> {
                        if (attempt < 3) sleeper.sleep(100L << attempt);
                    }
                }
            }
            log.info("Delivery round exhausted; job will compensate: taskId={}", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Do not print supplier URL, credentials, body or exception messages.
            log.warn("Delivery interrupted: taskId={}, errorType={}", taskId, e.getClass().getSimpleName());
        }
    }
}
