package xizhang.rc_xizhang_coding.entity;

import lombok.Data;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;

@Data
public class NotificationTask {
    private Long id;
    private String taskId;
    private String requestId;
    private String requestData;
    private boolean transferSuccess;
    private boolean processed;
    private DeliveryStatus deliveryStatus;

    public boolean pending() {
        return !transferSuccess && !processed && deliveryStatus == DeliveryStatus.PENDING;
    }
}
