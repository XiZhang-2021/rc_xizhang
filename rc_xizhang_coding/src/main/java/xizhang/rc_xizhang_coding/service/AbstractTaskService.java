package xizhang.rc_xizhang_coding.service;

import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.dto.CallbackRequest;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.dto.Submission;
import xizhang.rc_xizhang_coding.dto.SubmitRequest;
import xizhang.rc_xizhang_coding.dto.TaskView;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;
import xizhang.rc_xizhang_coding.mapper.TaskMapper;
import xizhang.rc_xizhang_coding.validation.RequestPolicy;

/** Shared persistence, idempotency and callback behavior for the three business services. */
public abstract class AbstractTaskService {
    private final TaskMapper mapper;
    private final RequestPolicy policy;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final TaskDispatcher dispatcher;

    protected AbstractTaskService(TaskMapper mapper, RequestPolicy policy, JsonMapper json,
                       TransactionTemplate transactions, TaskDispatcher dispatcher) {
        this.mapper = mapper;
        this.policy = policy;
        this.json = json;
        this.transactions = transactions;
        this.dispatcher = dispatcher;
    }

    public Submission submit(SubmitRequest request) {
        RequestData data = policy.validate(request);
        NotificationTask task = new NotificationTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setRequestId(request.requestId());
        task.setRequestData(json.writeValueAsString(data));
        task.setDeliveryStatus(DeliveryStatus.PENDING);
        try {
            transactions.executeWithoutResult(status -> mapper.insert(task));
        } catch (DuplicateKeyException e) {
            NotificationTask existing = mapper.findByRequestId(request.requestId());
            if (existing == null) throw e;
            RequestData stored = json.readValue(existing.getRequestData(), RequestData.class);
            if (!stored.equals(data)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "requestId already exists with different request content");
            return new Submission(TaskView.from(existing), false);
        }
        // The insert has committed before any network work is submitted.
        dispatcher.enqueue(mapper, task.getTaskId());
        return new Submission(TaskView.from(task), true);
    }

    public TaskView get(String taskId) { return TaskView.from(find(taskId)); }

    public void callback(CallbackRequest request, String key) {
        if (request == null || request.taskId() == null || request.requestId() == null || request.success() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId, requestId and success are required");
        }
        NotificationTask task = find(request.taskId());
        policy.checkCallback(json.readValue(task.getRequestData(), RequestData.class), key);
        if (!task.getRequestId().equals(request.requestId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Callback requestId does not match task");
        }
        if (request.success()) {
            transactions.executeWithoutResult(status -> mapper.markProcessed(task.getTaskId(), task.getRequestId()));
        }
    }

    private NotificationTask find(String taskId) {
        NotificationTask task = mapper.findByTaskId(taskId);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        return task;
    }
}
