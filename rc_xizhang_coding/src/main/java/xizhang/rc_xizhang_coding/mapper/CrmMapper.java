package xizhang.rc_xizhang_coding.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;

@Mapper
public interface CrmMapper extends TaskMapper {
    @Insert("""
        INSERT INTO crm_notification_task(task_id, request_id, request_data)
        VALUES(#{taskId}, #{requestId}, #{requestData})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(NotificationTask task);

    @Select("SELECT * FROM crm_notification_task WHERE task_id = #{taskId}")
    NotificationTask findByTaskId(String taskId);

    @Select("SELECT * FROM crm_notification_task WHERE request_id = #{requestId}")
    NotificationTask findByRequestId(String requestId);

    @Select("""
        SELECT id, task_id FROM crm_notification_task
        WHERE processed = FALSE AND transfer_success = FALSE AND delivery_status = 'PENDING'
          AND id > #{lastId} AND id <= #{maxId}
        ORDER BY id LIMIT #{batchSize}
        """)
    List<NotificationTask> findPending(@Param("lastId") long lastId,
                                      @Param("maxId") long maxId, @Param("batchSize") int batchSize);

    @Select("SELECT COALESCE(MAX(id), 0) FROM crm_notification_task")
    long maxId();

    // Only the delivery columns are changed: an early callback must never be overwritten.
    @Update("""
        UPDATE crm_notification_task SET transfer_success = #{success}, delivery_status = #{status}
        WHERE task_id = #{taskId} AND delivery_status = 'PENDING' AND transfer_success = FALSE
        """)
    int finishDelivery(@Param("taskId") String taskId, @Param("success") boolean success,
                       @Param("status") DeliveryStatus status);

    @Update("""
        UPDATE crm_notification_task SET processed = TRUE
        WHERE task_id = #{taskId} AND request_id = #{requestId} AND processed = FALSE
        """)
    int markProcessed(@Param("taskId") String taskId, @Param("requestId") String requestId);
}
