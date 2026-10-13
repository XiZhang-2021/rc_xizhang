package xizhang.rc_xizhang_coding.mapper;

import java.util.List;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;

/** Common contract only. The three business mappers provide SQL and are the only mapper beans. */
public interface TaskMapper {
    void insert(NotificationTask task);
    NotificationTask findByTaskId(String taskId);
    NotificationTask findByRequestId(String requestId);
    List<NotificationTask> findPending(long lastId, long maxId, int batchSize);
    long maxId();
    int finishDelivery(String taskId, boolean success, DeliveryStatus status);
    int markProcessed(String taskId, String requestId);
}
