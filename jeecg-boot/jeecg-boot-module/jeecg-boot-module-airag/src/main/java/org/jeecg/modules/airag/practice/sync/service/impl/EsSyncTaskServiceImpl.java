package org.jeecg.modules.airag.practice.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.sync.entity.EsSyncTask;
import org.jeecg.modules.airag.practice.sync.mapper.EsSyncTaskMapper;
import org.jeecg.modules.airag.practice.sync.service.IEsSyncTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

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
        // 查询是否已有相同文档和操作的同步任务，有则重置，无则新建，防止记录膨胀
        LambdaQueryWrapper<EsSyncTask> query = new LambdaQueryWrapper<EsSyncTask>()
                .eq(EsSyncTask::getDocumentId, documentId)
                .eq(EsSyncTask::getAction, action);
        
        List<EsSyncTask> list = this.list(query);
        EsSyncTask task;
        if (!list.isEmpty()) {
            task = list.get(0);
            task.setStatus(EsSyncTask.STATUS_PENDING)
                .setRetryCount(0)
                .setErrorMsg(null)
                .setUpdateTime(new Date());
            if (knowledgeBaseId != null) {
                task.setKnowledgeBaseId(knowledgeBaseId);
            }
            this.updateById(task);
            log.info("[Outbox] 重置已有同步任务: action={}, docId={}, taskId={}", action, documentId, task.getId());
        } else {
            task = new EsSyncTask()
                    .setAction(action)
                    .setDocumentId(documentId)
                    .setKnowledgeBaseId(knowledgeBaseId)
                    .setStatus(EsSyncTask.STATUS_PENDING)
                    .setRetryCount(0)
                    .setCreateTime(new Date())
                    .setUpdateTime(new Date());
            this.save(task);
            log.info("[Outbox] 创建新同步任务: action={}, docId={}, taskId={}", action, documentId, task.getId());
        }
        return task;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW) // 使用新事务，避免业务事务回滚导致无法更新状态
    public void completeTask(String documentId, String action) {
        LambdaQueryWrapper<EsSyncTask> query = new LambdaQueryWrapper<EsSyncTask>()
                .eq(EsSyncTask::getDocumentId, documentId)
                .eq(EsSyncTask::getAction, action);
        List<EsSyncTask> list = this.list(query);
        for (EsSyncTask task : list) {
            task.setStatus(EsSyncTask.STATUS_SUCCESS)
                .setUpdateTime(new Date());
            this.updateById(task);
            log.info("[Outbox] 同步任务处理成功: action={}, docId={}, taskId={}", action, documentId, task.getId());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTask(String documentId, String action, String errorMsg) {
        LambdaQueryWrapper<EsSyncTask> query = new LambdaQueryWrapper<EsSyncTask>()
                .eq(EsSyncTask::getDocumentId, documentId)
                .eq(EsSyncTask::getAction, action);
        List<EsSyncTask> list = this.list(query);
        for (EsSyncTask task : list) {
            task.setStatus(EsSyncTask.STATUS_FAILED)
                .setErrorMsg(errorMsg)
                .setUpdateTime(new Date());
            this.updateById(task);
            log.warn("[Outbox] 同步任务标记为失败: action={}, docId={}, error={}", action, documentId, errorMsg);
        }
    }
}
