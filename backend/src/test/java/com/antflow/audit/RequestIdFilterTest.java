package com.antflow.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class RequestIdFilterTest {
    @Test
    void logsRequestsAtOrAboveTheConfiguredThreshold() throws Exception {
        var filter = new RequestIdFilter();
        ReflectionTestUtils.setField(filter, "slowRequestThresholdMs", 0L);
        var logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("GET", "/api/workplace/overview");
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            });

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("path=/api/workplace/overview")
                    && message.contains("durationMs=") && message.contains("traceId="));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
