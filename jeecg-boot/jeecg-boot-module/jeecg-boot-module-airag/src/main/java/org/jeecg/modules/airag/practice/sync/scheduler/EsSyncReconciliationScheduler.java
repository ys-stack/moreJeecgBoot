package org.jeecg.modules.airag.practice.sync.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.sync.dto.EsSyncMessage;
import org.jeecg.modules.airag.practice.sync.entity.EsSyncTask;
import org.jeecg.modules.airag.practice.sync.producer.EsSyncProducer;
import org.jeecg.modules.airag.practice.sync.service.IEsSyncTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * ES 异步同步对账与自动重试定时任务 (Outbox 补偿机制)
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-10
 */
@Slf4j
@Component
@EnableScheduling // 在此显式启用定时任务，保证 standalone 模式下依然能够执行
public class EsSyncReconciliationScheduler {

    private static final int BATCH_SIZE = 100;

    @Value("${practice.es-sync.max-dispatch-attempts:10}")
    private int maxDispatchAttempts;

    @Value("${practice.es-sync.dispatch-timeout-ms:600000}")
    private long dispatchTimeoutMs;

    @Value("${practice.es-sync.success-retention-days:7}")
    private int successRetentionDays;

    @Autowired
    private IEsSyncTaskService esSyncTaskService;

    @Autowired
    private EsSyncProducer esSyncProducer;

    @Autowired
    private AiDocumentMapper aiDocumentMapper;

    /**
     * 自动重试与对账定时任务：每 5 分钟执行一次
     * 扫描待发送、发送失败或超时未确认的任务，分批重新投递到 MQ。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void reconcileTasks() {
        log.info("[Outbox对账] 开始扫描未完成的 ES 同步任务...");

        Date staleBefore = new Date(System.currentTimeMillis() - dispatchTimeoutMs);
        LambdaQueryWrapper<EsSyncTask> query = new LambdaQueryWrapper<EsSyncTask>()
                .and(wrapper -> wrapper
                        .in(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING, EsSyncTask.STATUS_FAILED)
                        .or(stale -> stale.eq(EsSyncTask::getStatus, EsSyncTask.STATUS_DISPATCHED)
                                .lt(EsSyncTask::getUpdateTime, staleBefore))
                        .or(stale -> stale.eq(EsSyncTask::getStatus, EsSyncTask.STATUS_PROCESSING)
                                .lt(EsSyncTask::getUpdateTime, staleBefore)))
                .lt(EsSyncTask::getRetryCount, maxDispatchAttempts)
                .orderByAsc(EsSyncTask::getCreateTime)
                .last("LIMIT " + BATCH_SIZE);

        List<EsSyncTask> tasks = esSyncTaskService.list(query);
        if (tasks.isEmpty()) {
            log.info("[Outbox对账] 未发现需要补偿的同步任务");
            return;
        }

        log.info("[Outbox对账] 发现 {} 个未完成的同步任务，开始执行补偿投递...", tasks.size());
        for (EsSyncTask task : tasks) {
            try {
                if (!esSyncTaskService.claimTask(task)) {
                    log.debug("[Outbox对账] 任务已被其他实例抢占: taskId={}", task.getId());
                    continue;
                }

                // INDEX 源文档被删除时仍要投递，由消费者执行幂等清理。
                if (EsSyncMessage.ACTION_INDEX.equalsIgnoreCase(task.getAction())) {
                    AiDocument doc = aiDocumentMapper.selectById(task.getDocumentId());
                    if (doc == null) {
                        // 仍投递该任务，由消费者执行幂等清理，防止历史 INDEX 残留。
                        log.warn("[Outbox对账] 源文档已删除，将投递 INDEX 任务执行 ES 清理: docId={}", task.getDocumentId());
                    }
                }

                // 抢占成功后再投递；发送异常会被记录为 FAILED，等待下轮补偿。
                log.info("[Outbox对账] 重新投递同步消息: taskId={}, action={}, docId={}, retryCount={}",
                        task.getId(), task.getAction(), task.getDocumentId(), task.getRetryCount() + 1);

                esSyncProducer.sendSyncMessage(task.getId(), task.getAction(),
                        task.getDocumentId(), task.getKnowledgeBaseId());
                esSyncTaskService.markDispatched(task.getId());

            } catch (Exception e) {
                log.error("[Outbox对账] 补偿投递任务异常: taskId={}", task.getId(), e);
                esSyncTaskService.failTask(task.getId(), e.getMessage());
            }
        }
    }

    /**
     * 将耗尽投递次数的任务转入 DEAD，并小批量清理已成功的历史任务。
     */
    @Scheduled(cron = "0 15 * * * ?")
    public void maintainTasks() {
        Date now = new Date();
        esSyncTaskService.update(new LambdaUpdateWrapper<EsSyncTask>()
                .ne(EsSyncTask::getStatus, EsSyncTask.STATUS_SUCCESS)
                .ne(EsSyncTask::getStatus, EsSyncTask.STATUS_DEAD)
                .ge(EsSyncTask::getRetryCount, maxDispatchAttempts)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_DEAD)
                .set(EsSyncTask::getErrorMsg, "MQ 投递次数已耗尽，需要人工处理")
                .set(EsSyncTask::getUpdateTime, now));

        long retentionMs = successRetentionDays * 24L * 60 * 60 * 1000;
        Date retentionBefore = new Date(System.currentTimeMillis() - retentionMs);
        esSyncTaskService.remove(new LambdaQueryWrapper<EsSyncTask>()
                .eq(EsSyncTask::getStatus, EsSyncTask.STATUS_SUCCESS)
                .lt(EsSyncTask::getUpdateTime, retentionBefore)
                .last("LIMIT 1000"));
    }
}
