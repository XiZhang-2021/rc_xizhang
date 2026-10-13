package xizhang.rc_xizhang_coding.support;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.config.NotificationProperties;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;

public final class TestFixtures {
    public static NotificationProperties properties(String origin) {
        return new NotificationProperties("internal-test", Map.of("demo",
                new NotificationProperties.Supplier(origin, "callback-test")), Duration.ofSeconds(1), 2, 2, 10, true);
    }
    public static NotificationTask task() {
        NotificationTask task = new NotificationTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setRequestId("request-1");
        task.setDeliveryStatus(DeliveryStatus.PENDING);
        task.setRequestData(new JsonMapper().writeValueAsString(new RequestData(
                "http://localhost:9090/notify", "POST", Map.of(), "body")));
        return task;
    }
}
