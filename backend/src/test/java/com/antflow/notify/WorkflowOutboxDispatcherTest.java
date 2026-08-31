package com.antflow.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
