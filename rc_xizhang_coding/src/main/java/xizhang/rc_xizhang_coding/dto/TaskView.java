package xizhang.rc_xizhang_coding.dto;

import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;

public record TaskView(String taskId, String requestId, boolean transferSuccess,
                       boolean processed, DeliveryStatus deliveryStatus) {
    public static TaskView from(NotificationTask task) {
        return new TaskView(task.getTaskId(), task.getRequestId(), task.isTransferSuccess(),
                task.isProcessed(), task.getDeliveryStatus());
    }
}
