package org.jeecg.modules.airag.practice.sync.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.sync.dto.EsSyncMessage;
import org.jeecg.modules.airag.practice.sync.entity.EsSyncTask;
import org.jeecg.modules.airag.practice.sync.producer.EsSyncProducer;
import org.jeecg.modules.airag.practice.sync.service.IEsSyncTaskService;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private IEsSyncTaskService esSyncTaskService;

    @Autowired
    private EsSyncProducer esSyncProducer;

    @Autowired
    private AiDocumentMapper aiDocumentMapper;

    /**
     * 自动重试与对账定时任务：每 5 分钟执行一次
     * 扫描状态为 PENDING / FAILED 且重试次数 < 5 的同步任务，重新投递到 MQ
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void reconcileTasks() {
        log.info("[Outbox对账] 开始扫描未完成的 ES 同步任务...");

        LambdaQueryWrapper<EsSyncTask> query = new LambdaQueryWrapper<EsSyncTask>()
                .in(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING, EsSyncTask.STATUS_FAILED)
                .lt(EsSyncTask::getRetryCount, 5)
                .orderByAsc(EsSyncTask::getCreateTime);

        List<EsSyncTask> tasks = esSyncTaskService.list(query);
        if (tasks.isEmpty()) {
            log.info("[Outbox对账] 未发现需要补偿的同步任务");
            return;
        }

        log.info("[Outbox对账] 发现 {} 个未完成的同步任务，开始执行补偿投递...", tasks.size());
        for (EsSyncTask task : tasks) {
            try {
                // 1. 对于 INDEX (写索引) 操作，若 MySQL 中的主文档已被删除，则该任务无须再做同步，直接置为失败即可
                if (EsSyncMessage.ACTION_INDEX.equalsIgnoreCase(task.getAction())) {
                    AiDocument doc = aiDocumentMapper.selectById(task.getDocumentId());
                    if (doc == null) {
                        task.setStatus(EsSyncTask.STATUS_FAILED)
                            .setErrorMsg("MySQL中找不到对应的源文档，终止同步")
                            .setUpdateTime(new Date());
                        esSyncTaskService.updateById(task);
                        log.warn("[Outbox对账] 源文档已被删除，取消索引同步: docId={}", task.getDocumentId());
                        continue;
                    }
                }

                // 2. 增加重试次数并记录重试状态
                task.setRetryCount(task.getRetryCount() + 1)
                    .setUpdateTime(new Date());
                esSyncTaskService.updateById(task);

                // 3. 重新向 MQ 投递同步消息
                log.info("[Outbox对账] 重新投递同步消息: taskId={}, action={}, docId={}, retryCount={}",
                        task.getId(), task.getAction(), task.getDocumentId(), task.getRetryCount());
                
                esSyncProducer.sendSyncMessage(task.getAction(), task.getDocumentId(), task.getKnowledgeBaseId());

            } catch (Exception e) {
                log.error("[Outbox对账] 补偿投递任务异常: taskId={}", task.getId(), e);
            }
        }
    }
}
