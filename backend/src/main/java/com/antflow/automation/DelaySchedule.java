package com.antflow.automation;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class DelaySchedule {
    private DelaySchedule() {}

    public static OffsetDateTime calculate(JsonNode props, OffsetDateTime now, ZoneId zoneId) {
        String mode = props.path("mode").asText("DURATION");
        if ("UNTIL_TIME".equals(mode)) {
            try {
                LocalTime time = LocalTime.parse(props.path("time").asText());
                ZonedDateTime localNow = now.atZoneSameInstant(zoneId);
                ZonedDateTime target = localNow.toLocalDate().atTime(time).atZone(zoneId);
                return target.isAfter(localNow) ? target.toOffsetDateTime() : now;
            } catch (Exception e) {
                throw new BizException("BAD_DELAY", "延时节点的当天时刻无效");
            }
        }

        long amount = props.path("amount").asLong(0);
        String unit = props.path("unit").asText();
        if (amount <= 0) {
            throw new BizException("BAD_DELAY", "固定延时必须大于 0");
        }
        Duration duration = switch (unit) {
            case "MINUTES" -> Duration.ofMinutes(amount);
            case "HOURS" -> Duration.ofHours(amount);
            case "DAYS" -> Duration.ofDays(amount);
            default -> throw new BizException("BAD_DELAY", "延时单位无效");
        };
        if (duration.compareTo(Duration.ofDays(365)) > 0) {
            throw new BizException("BAD_DELAY", "固定延时不能超过 365 天");
        }
        return now.plus(duration);
    }
}
