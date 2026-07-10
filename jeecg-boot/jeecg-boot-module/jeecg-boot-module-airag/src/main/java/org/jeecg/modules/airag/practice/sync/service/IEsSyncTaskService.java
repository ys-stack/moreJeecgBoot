package org.jeecg.modules.airag.practice.sync.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.sync.entity.EsSyncTask;

/**
 * ES数据同步任务服务接口
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-10
 */
public interface IEsSyncTaskService extends IService<EsSyncTask> {

    /**
     * 保存或重置待同步任务至本地消息表
     *
     * @param action          操作类型: INDEX/DELETE
     * @param documentId      文档ID
     * @param knowledgeBaseId 知识库ID
     * @return 任务记录
     */
    EsSyncTask saveTask(String action, String documentId, String knowledgeBaseId);

    /**
     * 标记同步任务为成功
     *
     * @param documentId 文档ID
     * @param action     操作类型
     */
    void completeTask(String documentId, String action);

    /**
     * 标记同步任务为失败并记录错误信息
     *
     * @param documentId 文档ID
     * @param action     操作类型
     * @param errorMsg   异常信息
     */
    void failTask(String documentId, String action, String errorMsg);
}
