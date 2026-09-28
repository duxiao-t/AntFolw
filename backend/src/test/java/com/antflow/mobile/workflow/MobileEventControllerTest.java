package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.notify.NotificationEvent;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class MobileEventControllerTest {

    @Test
    void deliversCcEventsThatCarryNoTaskId() {
        // CC_ASSIGNED 没有任务 id（taskId = null）。原来是 Map.of(...) 拼 payload，撞 null 直接
        // NPE：异常从 publishEvent 冒回 outbox 投递器 → 这条抄送重试 10 次后进 DEAD，
        // 表现成"站内通知写了、SSE 永远推不出去"。
        MobileEventController controller = new MobileEventController(mock(AuthorizationService.class));
        RecordingEmitter assigned = new RecordingEmitter(false);
        controller.register(7L, assigned);

        assertThatCode(() -> controller.onNotification(
            new NotificationEvent(this, "CC_ASSIGNED", 11L, null, 7L, "cc")))
            .doesNotThrowAnyException();

        assertThat(assigned.sent).isEqualTo(1);
        Map<String, Object> payload = assigned.dataPayload();
        assertThat(payload).containsEntry("eventType", "CC_ASSIGNED")
            .containsEntry("instanceId", 11L);
        // key 必须还在（值为 null），前端按 key 判断要不要跳任务详情。
        assertThat(payload).containsKey("taskId");
        assertThat(payload.get("taskId")).isNull();
    }

    @Test
    void sendsOnlyToTheAssignedUserAndDropsBrokenConnections() {
        MobileEventController controller = new MobileEventController(mock(AuthorizationService.class));
        RecordingEmitter assigned = new RecordingEmitter(false);
        RecordingEmitter other = new RecordingEmitter(false);
        controller.register(7L, assigned);
        controller.register(8L, other);

        controller.onNotification(new NotificationEvent(this, "TASK_ASSIGNED", 11L, 12L, 7L, "new"));

        assertThat(assigned.sent).isEqualTo(1);
        assertThat(other.sent).isZero();

        RecordingEmitter broken = new RecordingEmitter(true, true);
        controller.register(7L, broken);
        controller.onNotification(new NotificationEvent(this, "TASK_RETURNED", 11L, 13L, 7L, "returned"));

        assertThat(controller.connectionCount(7L)).isEqualTo(1);
        assertThat(broken.completions).isZero();
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final boolean broken;
        private int sent;
        private int completions;
        private SseEventBuilder last;

        private final boolean completionBroken;

        private RecordingEmitter(boolean broken) {
            this(broken, false);
        }

        private RecordingEmitter(boolean broken, boolean completionBroken) {
            this.broken = broken;
            this.completionBroken = completionBroken;
        }

        /** 事件体里的数据部分（`event:` 那一条是字符串，跳过）。 */
        @SuppressWarnings("unchecked")
        Map<String, Object> dataPayload() {
            if (last == null) return Map.of();
            for (ResponseBodyEmitter.DataWithMediaType item : last.build()) {
                if (item.getData() instanceof Map<?, ?> data) return (Map<String, Object>) data;
            }
            return Map.of();
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) throw new IOException("closed");
            sent++;
            last = builder;
        }

        @Override
        public void complete() {
            completions++;
            if (completionBroken) throw new IllegalStateException("already closed");
            super.complete();
        }
    }
}
