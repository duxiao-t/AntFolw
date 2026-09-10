package com.antflow;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The isolated Docker clone must never run copied schedules or external deliveries. */
@Configuration
@Profile("!test-isolated")
@EnableScheduling
class ScheduledWorkConfiguration {
}
