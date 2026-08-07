package com.antflow.automation;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelayScheduleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    @Test
    void fixedDurationAddsConfiguredAmount() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-05T10:00:00+08:00");
        var props = json.readTree("""
            {"mode":"DURATION","amount":90,"unit":"MINUTES"}
            """);

        assertThat(DelaySchedule.calculate(props, now, zone))
            .isEqualTo(OffsetDateTime.parse("2026-08-05T11:30:00+08:00"));
    }

    @Test
    void untilTimeUsesTodayAndContinuesImmediatelyWhenAlreadyPast() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-05T10:00:00+08:00");

        assertThat(DelaySchedule.calculate(
            json.readTree("{\"mode\":\"UNTIL_TIME\",\"time\":\"11:15\"}"), now, zone))
            .isEqualTo(OffsetDateTime.parse("2026-08-05T11:15:00+08:00"));
        assertThat(DelaySchedule.calculate(
            json.readTree("{\"mode\":\"UNTIL_TIME\",\"time\":\"09:15\"}"), now, zone))
            .isEqualTo(now);
    }

    @Test
    void rejectsZeroAndMoreThan365Days() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-05T10:00:00+08:00");
        assertThatThrownBy(() -> DelaySchedule.calculate(
            json.readTree("{\"mode\":\"DURATION\",\"amount\":0,\"unit\":\"DAYS\"}"), now, zone))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> DelaySchedule.calculate(
            json.readTree("{\"mode\":\"DURATION\",\"amount\":366,\"unit\":\"DAYS\"}"), now, zone))
            .isInstanceOf(BizException.class);
    }
}
