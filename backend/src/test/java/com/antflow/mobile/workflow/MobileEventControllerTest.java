package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.notify.NotificationEvent;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MobileEventControllerTest {

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

        private final boolean completionBroken;

        private RecordingEmitter(boolean broken) {
            this(broken, false);
        }

        private RecordingEmitter(boolean broken, boolean completionBroken) {
            this.broken = broken;
            this.completionBroken = completionBroken;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) throw new IOException("closed");
            sent++;
        }

        @Override
        public void complete() {
            completions++;
            if (completionBroken) throw new IllegalStateException("already closed");
            super.complete();
        }
    }
}
