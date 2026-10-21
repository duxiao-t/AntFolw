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

    /**
     * 续租：投递期间把本 worker 名下 RUNNING 作业的 locked_at 推到 now。
     *
     * <p>为什么需要：{@link #recoverStaleJobs()} 只看 `locked_at < now - stale-lock-timeout`。
     * webhook 本身有界，但 `completeAutomation` 要等实例行锁（媒体校验等场景可能很久），
     * 没有续租就会在投递中途被抢回 SCHEDULED 再投一遍（webhook/完成动作重复）。
     * 与 outbox 的 `lease-renew-interval-ms` 是同一套做法。
     */
    @Scheduled(fixedDelayString = "${antflow.automation.lease-renew-interval-ms:30000}")
    public void renewLeases() {
        int renewed = jobs.renewOwnedLeases(workerId);
        if (renewed > 0) log.debug("Renewed {} workflow job leases", renewed);
    }

    /** 把"卡在 RUNNING 且租约过期"的作业放回队列；有续租后这只会命中真正失联的 worker。 */
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
