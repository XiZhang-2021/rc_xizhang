package xizhang.rc_xizhang_coding.service;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.client.SupplierClient;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;
import xizhang.rc_xizhang_coding.lock.MysqlTaskLock;
import xizhang.rc_xizhang_coding.mapper.TaskMapper;
import xizhang.rc_xizhang_coding.support.TestFixtures;
import xizhang.rc_xizhang_coding.validation.RequestPolicy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static xizhang.rc_xizhang_coding.client.SupplierClient.Outcome.*;

class DeliveryWorkerTest {
    TaskMapper mapper = mock(TaskMapper.class);
    MysqlTaskLock locks = mock(MysqlTaskLock.class);
    MysqlTaskLock.Lease lease = mock(MysqlTaskLock.Lease.class);
    SupplierClient client = mock(SupplierClient.class);
    NotificationTask task = TestFixtures.task();
    List<Long> waits = new ArrayList<>();
    DeliveryWorker worker;

    @BeforeEach void setup() throws Exception {
        when(locks.tryAcquire(task.getTaskId())).thenReturn(lease);
        when(mapper.findByTaskId(task.getTaskId())).thenReturn(task);
        worker = new DeliveryWorker(locks, client, new JsonMapper(),
                new RequestPolicy(TestFixtures.properties("http://localhost:9090")), waits::add);
    }

    @Test void retriesExactlyThreeTimesAndKeepsPendingAfterExhaustion() throws Exception {
        when(client.send(any(), any())).thenReturn(RETRY);
        worker.deliver(mapper, task.getTaskId());
        verify(client, times(4)).send(any(), any());
        assertThat(waits).containsExactly(100L, 200L, 400L);
        verify(mapper, never()).finishDelivery(anyString(), anyBoolean(), any());
        verify(lease).close();
    }

    @Test void successStopsRetryAndOnlyUpdatesDeliveryColumns() throws Exception {
        when(client.send(any(), any())).thenReturn(RETRY, SUCCESS);
        worker.deliver(mapper, task.getTaskId());
        verify(client, times(2)).send(any(), any());
        verify(mapper).finishDelivery(task.getTaskId(), true, DeliveryStatus.SUCCESS);
        verify(mapper, never()).markProcessed(anyString(), anyString());
        assertThat(waits).containsExactly(100L);
    }

    @Test void duplicateIsNotSuccessAndDoesNotRetry() throws Exception {
        when(client.send(any(), any())).thenReturn(DUPLICATE);
        worker.deliver(mapper, task.getTaskId());
        verify(client).send(any(), any());
        verify(mapper).finishDelivery(task.getTaskId(), false, DeliveryStatus.DUPLICATE);
        assertThat(waits).isEmpty();
    }

    @Test void earlyCallbackPreventsNextAttempt() throws Exception {
        when(client.send(any(), any())).thenAnswer(invocation -> { task.setProcessed(true); return RETRY; });
        worker.deliver(mapper, task.getTaskId());
        verify(client).send(any(), any());
        verify(mapper, never()).finishDelivery(anyString(), anyBoolean(), any());
    }

    @Test void alreadyProcessedOrDeliveredTasksAreNotSent() throws Exception {
        task.setProcessed(true);
        worker.deliver(mapper, task.getTaskId());
        task.setProcessed(false);
        task.setTransferSuccess(true);
        worker.deliver(mapper, task.getTaskId());
        verifyNoInteractions(client);
    }

    @Test void noLockMeansNoDatabaseReadOrSend() throws Exception {
        when(locks.tryAcquire(task.getTaskId())).thenReturn(null);
        worker.deliver(mapper, task.getTaskId());
        verifyNoInteractions(mapper, client);
    }

    @Test void exceptionsStillReleaseLock() throws Exception {
        when(client.send(any(), any())).thenThrow(new IllegalStateException("test"));
        worker.deliver(mapper, task.getTaskId());
        verify(lease).close();
    }

    @Test void lostLockPreventsStaleResultWrite() throws Exception {
        when(client.send(any(), any())).thenReturn(SUCCESS);
        doNothing().doThrow(new java.sql.SQLException("lost")).when(lease).checkOwned();
        worker.deliver(mapper, task.getTaskId());
        verify(mapper, never()).finishDelivery(anyString(), anyBoolean(), any());
        verify(lease).close();
    }
}
