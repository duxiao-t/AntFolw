package com.antflow.automation;

import com.antflow.engine.ProcessEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class AutomationJobScheduler {
    private final WorkflowJobService jobs;
    private final WebhookClient webhookClient;
    private final ProcessEngine processEngine;
    private final String workerId = UUID.randomUUID().toString();

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        int recovered = jobs.recoverStale();
        if (recovered > 0) log.info("Recovered {} stale workflow jobs", recovered);
    }

    @Scheduled(fixedDelayString = "${antflow.automation.poll-interval-ms:5000}")
    public void poll() {
        for (int i = 0; i < 10; i++) {
            WorkflowJob job = jobs.claimDue(workerId);
            if (job == null) return;
            execute(job);
        }
    }

    @Scheduled(fixedDelayString = "${antflow.automation.recovery-interval-ms:60000}")
    public void recoverStaleJobs() {
        jobs.recoverStale();
    }

    private void execute(WorkflowJob job) {
        try {
            if ("TASK_TIMEOUT".equals(job.getJobType())) {
                processEngine.completeTaskTimeout(job.getId());
                return;
            }
            if ("DELAY".equals(job.getJobType())) {
                processEngine.completeAutomation(job.getId());
                return;
            }
            WebhookClient.DeliveryResult result = webhookClient.send(job, jobs.payload(job));
            if (result.successful()) {
                processEngine.completeAutomation(job.getId());
            } else {
                jobs.recordFailure(job.getId(), "Webhook returned HTTP " + result.statusCode());
            }
        } catch (Exception e) {
            log.warn("Automation job {} failed: {}", job.getId(), e.toString());
            jobs.recordFailure(job.getId(), e.getMessage());
        }
    }
}
