package xizhang.rc_xizhang_coding.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xizhang.rc_xizhang_coding.dto.CallbackRequest;
import xizhang.rc_xizhang_coding.dto.Submission;
import xizhang.rc_xizhang_coding.dto.SubmitRequest;
import xizhang.rc_xizhang_coding.dto.TaskView;
import xizhang.rc_xizhang_coding.service.InventoryService;

/** 购买后通知库存系统。 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }

    @PostMapping("/tasks")
    public ResponseEntity<TaskView> submit(@RequestBody SubmitRequest request) {
        Submission result = service.submit(request);
        return ResponseEntity.status(result.created() ? 202 : 200).body(result.task());
    }

    @GetMapping("/tasks/{taskId}")
    public TaskView get(@PathVariable String taskId) { return service.get(taskId); }

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@RequestHeader(value = "X-Callback-Key", required = false) String key,
                                         @RequestBody CallbackRequest request) {
        service.callback(request, key);
        return ResponseEntity.noContent().build();
    }
}
