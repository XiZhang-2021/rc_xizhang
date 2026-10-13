package xizhang.rc_xizhang_coding.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.mapper.InventoryMapper;
import xizhang.rc_xizhang_coding.validation.RequestPolicy;

/** 购买后通知库存系统，只访问本业务的 Mapper。 */
@Service
public class InventoryService extends AbstractTaskService {
    public InventoryService(InventoryMapper mapper, RequestPolicy policy, JsonMapper json,
                         TransactionTemplate transactions, TaskDispatcher dispatcher) {
        super(mapper, policy, json, transactions, dispatcher);
    }
}
