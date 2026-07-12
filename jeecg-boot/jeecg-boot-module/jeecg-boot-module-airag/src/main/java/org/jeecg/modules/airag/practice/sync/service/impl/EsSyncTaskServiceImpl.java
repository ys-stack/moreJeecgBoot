package org.jeecg.modules.airag.practice.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.sync.entity.EsSyncTask;
import org.jeecg.modules.airag.practice.sync.mapper.EsSyncTaskMapper;
import org.jeecg.modules.airag.practice.sync.service.IEsSyncTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * ES数据同步任务服务实现类 (Transactional Outbox)
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-10
 */
@Slf4j
@Service
public class EsSyncTaskServiceImpl extends ServiceImpl<EsSyncTaskMapper, EsSyncTask> implements IEsSyncTaskService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EsSyncTask saveTask(String action, String documentId, String knowledgeBaseId) {
        // 每个业务事件使用独立任务，避免旧 MQ 消息完成或覆盖后续任务。
        EsSyncTask task = new EsSyncTask()
                .setAction(action)
                .setDocumentId(documentId)
                .setKnowledgeBaseId(knowledgeBaseId)
                .setStatus(EsSyncTask.STATUS_PENDING)
                .setRetryCount(0)
                .setCreateTime(new Date())
                .setUpdateTime(new Date());
        this.save(task);
        log.info("[Outbox] 创建同步任务: action={}, docId={}, taskId={}", action, documentId, task.getId());
        return task;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW) // 使用新事务，避免业务事务回滚导致无法更新状态
    public void completeTask(String taskId) {
        if (taskId == null) {
            return;
        }
        updateStatus(taskId, EsSyncTask.STATUS_SUCCESS, null);
        log.info("[Outbox] 同步任务处理成功: taskId={}", taskId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTask(String taskId, String errorMsg) {
        if (taskId == null) {
            return;
        }
        LambdaUpdateWrapper<EsSyncTask> update = new LambdaUpdateWrapper<EsSyncTask>()
                .eq(EsSyncTask::getId, taskId)
                .ne(EsSyncTask::getStatus, EsSyncTask.STATUS_SUCCESS)
                .ne(EsSyncTask::getStatus, EsSyncTask.STATUS_DEAD)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_FAILED)
                .set(EsSyncTask::getErrorMsg, truncate(errorMsg))
                .set(EsSyncTask::getUpdateTime, new Date());
        this.update(update);
        log.warn("[Outbox] 同步任务标记为失败: taskId={}, error={}", taskId, errorMsg);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimTask(EsSyncTask task) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        LambdaUpdateWrapper<EsSyncTask> update = new LambdaUpdateWrapper<EsSyncTask>()
                .eq(EsSyncTask::getId, task.getId())
                .eq(EsSyncTask::getStatus, task.getStatus())
                .eq(EsSyncTask::getRetryCount, retryCount)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_PROCESSING)
                .set(EsSyncTask::getRetryCount, retryCount + 1)
                .set(EsSyncTask::getUpdateTime, new Date());
        return this.update(update);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatched(String taskId) {
        // 消费者可能在发送线程返回前完成，不能把 SUCCESS/FAILED 覆盖回 DISPATCHED。
        LambdaUpdateWrapper<EsSyncTask> update = new LambdaUpdateWrapper<EsSyncTask>()
                .eq(EsSyncTask::getId, taskId)
                .in(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING, EsSyncTask.STATUS_PROCESSING)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_DISPATCHED)
                .set(EsSyncTask::getErrorMsg, null)
                .set(EsSyncTask::getUpdateTime, new Date());
        this.update(update);
    }

    private void updateStatus(String taskId, String status, String errorMsg) {
        LambdaUpdateWrapper<EsSyncTask> update = new LambdaUpdateWrapper<EsSyncTask>()
                .eq(EsSyncTask::getId, taskId)
                .set(EsSyncTask::getStatus, status)
                .set(EsSyncTask::getErrorMsg, errorMsg)
                .set(EsSyncTask::getUpdateTime, new Date());
        this.update(update);
    }

    private String truncate(String errorMsg) {
        if (errorMsg == null || errorMsg.length() <= 2000) {
            return errorMsg;
        }
        return errorMsg.substring(0, 2000);
    }
}
