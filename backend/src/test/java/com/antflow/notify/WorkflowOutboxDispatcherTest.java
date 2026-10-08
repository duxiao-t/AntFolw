package com.antflow.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowOutboxDispatcherTest {

    @Test
    void listenerFailureReturnsOutboxEventToRetryQueue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        NotificationListener failing = event -> {
            throw new IllegalStateException("channel down");
        };
        NotificationPublisher publisher = new NotificationPublisher(events, List.of(failing));
        WorkflowOutboxDispatcher dispatcher = new WorkflowOutboxDispatcher(
            jdbc, new ObjectMapper(), publisher);
        UUID eventId = UUID.randomUUID();

        dispatcher.deliver(new WorkflowOutboxDispatcher.Event(
            eventId, 501L, "TASK_ASSIGNED", 8L,
            "{\"instanceId\":501,\"taskId\":401}", 1));

        verify(events).publishEvent(any(NotificationEvent.class));
        verify(jdbc).update(contains("SET status = CASE"), eq("channel down"),
            eq(eventId), anyString());
    }

    /**
     * 同一个 task 的**两次**通知（撤回后重新指派、改派回原审批人）必须是两个不同的键：
     * 早先退回 `type:instanceId:taskId:userId`，渠道表的 ON CONFLICT DO NOTHING 会把第二次静默丢掉。
     */
    @Test
    void distinctEventsForTheSameTaskGetDistinctDeliveryKeys() {
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        WorkflowOutboxDispatcher dispatcher = new WorkflowOutboxDispatcher(
            mock(JdbcTemplate.class), new ObjectMapper(), publisher);
        String payload = "{\"instanceId\":501,\"taskId\":401}";

        dispatcher.deliver(new WorkflowOutboxDispatcher.Event(
            UUID.randomUUID(), 501L, "TASK_ASSIGNED", 8L, payload, 1));
        dispatcher.deliver(new WorkflowOutboxDispatcher.Event(
            UUID.randomUUID(), 501L, "TASK_ASSIGNED", 8L, payload, 1));

        ArgumentCaptor<NotificationEvent> events = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishReliable(events.capture());
        String first = events.getAllValues().get(0).getDeliveryKey();
        String second = events.getAllValues().get(1).getDeliveryKey();
        assertThat(first).isNotEqualTo(second).contains("TASK_ASSIGNED:501:");

        // 同一条 outbox 行重投（attempt 2）仍然是同一个键——去重靠它，不能一次一换。
        UUID eventId = UUID.randomUUID();
        NotificationPublisher retrying = mock(NotificationPublisher.class);
        WorkflowOutboxDispatcher again = new WorkflowOutboxDispatcher(
            mock(JdbcTemplate.class), new ObjectMapper(), retrying);
        again.deliver(new WorkflowOutboxDispatcher.Event(
            eventId, 501L, "TASK_ASSIGNED", 8L, payload, 1));
        again.deliver(new WorkflowOutboxDispatcher.Event(
            eventId, 501L, "TASK_ASSIGNED", 8L, payload, 2));
        ArgumentCaptor<NotificationEvent> retries = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(retrying, org.mockito.Mockito.times(2)).publishReliable(retries.capture());
        assertThat(retries.getAllValues().get(0).getDeliveryKey())
            .isEqualTo(retries.getAllValues().get(1).getDeliveryKey());
    }

    /** 续租只碰**本 worker 自己**持有的 RUNNING 行：崩掉的进程续不了，它的行照样能被回收。 */
    @Test
    void renewsOnlyLeasesItOwns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkflowOutboxDispatcher dispatcher = new WorkflowOutboxDispatcher(
            jdbc, new ObjectMapper(), mock(NotificationPublisher.class));

        dispatcher.renewLeases();

        ArgumentCaptor<String> worker = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("locked_by = ?"), worker.capture());
        assertThat(worker.getValue()).isNotBlank();
        verify(jdbc).update(contains("status = 'RUNNING'"), anyString());
    }

    @Test
    void ccRoundNotificationUsesOneStableDeliveryKeyForEveryChannel() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        WorkflowOutboxDispatcher dispatcher = new WorkflowOutboxDispatcher(
            jdbc, new ObjectMapper(), publisher);

        dispatcher.deliver(new WorkflowOutboxDispatcher.Event(UUID.randomUUID(), 501L,
            "CC_ASSIGNED", 8L,
            "{\"instanceId\":501,\"roundNo\":2}", 1));

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(publisher).publishReliable(event.capture());
        assertThat(event.getValue().getMessage()).isEqualTo("您收到本轮抄送汇总");
        assertThat(event.getValue().getDeliveryKey()).isEqualTo("CC_ASSIGNED:501:2:8");
    }
}
