package org.jeecg.modules.airag.practice.doc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;

import java.util.List;

/**
 * AI知识库 Service
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
public interface IAiKnowledgeBaseService extends IService<AiKnowledgeBase> {

    /**
     * 删除知识库及其下所有文档和分片
     *
     * @param kbId 知识库ID
     * @return 删除的文档数
     */
    int deleteWithDocuments(String kbId);

    /**
     * 查询当前用户有权访问的知识库列表
     * 过滤规则：role_code 为空表示所有人可见，否则用户角色需包含其中任一角色
     *
     * @param userRoleCodes 用户拥有的角色编码列表
     * @return 可访问的知识库列表
     */
    List<AiKnowledgeBase> listAccessibleByUser(List<String> userRoleCodes);
}
