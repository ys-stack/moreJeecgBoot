package org.jeecg.modules.airag.practice.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.apache.shiro.authz.AuthorizationException;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiKnowledgeBaseMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/*
 * @Author: ys
 * @Date: 2026/7/27 星期一 23:11
 * @Desc: 知识库、文档、分片统一资源授权
 */
@Service
public class KnowledgeAccessService {
    /** 知识库数据访问。 */
    @Resource
    private AiKnowledgeBaseMapper knowledgeBaseMapper;
    /** 文档数据访问。 */
    @Resource
    private AiDocumentMapper documentMapper;
    /** 统一身份与角色解析。 */
    @Resource
    private PracticeSecurityContext securityContext;

    /*
     * @Author: ys
     * @Date: 2026/7/27 星期一 23:11
     * @Desc: 返回用户能访问的知识库列表
     */
    public List<String> readableKnowledgeBaseIds(LoginUser user) {
        Set<String> roles = securityContext.roles(user);
        return knowledgeBaseMapper.selectList(
                        new LambdaQueryWrapper<AiKnowledgeBase>()
                                .eq(AiKnowledgeBase::getStatus, "active"))
                .stream()
                .filter(kb -> isReadable(kb, roles))
                .map(AiKnowledgeBase::getId)
                .toList();
    }

    /*
     * @Author: ys
     * @Date: 2026/7/27 星期一 23:12
     * @Desc: 验证知识库是否可访问
     */
    public AiKnowledgeBase requireReadableKnowledgeBase(String kbId, LoginUser user) {
        if (user == null || kbId == null || kbId.isBlank()) {
            throw new AuthorizationException("知识库不存在或无权访问");
        }
        AiKnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || !"active".equals(kb.getStatus()) || !isReadable(kb, securityContext.roles(user))) {
            // 对外统一返回无权/不存在，避免枚举资源 ID。
            throw new AuthorizationException("知识库不存在或无权访问");
        }
        return kb;
    }

    /**
     * 校验用户是否拥有知识库管理权限：管理员或知识库创建人可以管理。
     */
    public AiKnowledgeBase requireManageableKnowledgeBase(String kbId, LoginUser user) {
        AiKnowledgeBase knowledgeBase = requireReadableKnowledgeBase(kbId, user);
        boolean owner = user.getId().equals(knowledgeBase.getCreateBy())
                || (user.getUsername() != null && user.getUsername().equals(knowledgeBase.getCreateBy()));
        if (!securityContext.isAdmin(user) && !owner) {
            throw new AuthorizationException("知识库不存在或无权管理");
        }
        return knowledgeBase;
    }

    /*
     * @Author: ys
     * @Date: 2026/7/27 星期一 23:13
     * @Desc: 验证文档是否可访问
     */
    public AiDocument requireReadableDocument(String documentId, LoginUser user) {
        AiDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new AuthorizationException("文档不存在或无权访问");
        }
        requireManageableKnowledgeBase(document.getKnowledgeBaseId(), user);
        return document;
    }

    /*
     * @Author: ys
     * @Date: 2026/7/27 星期一 23:13
     * @Desc: 验证文档是否可管理
     */
    public AiDocument requireManageableDocument(String documentId, LoginUser user) {
        AiDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new AuthorizationException("文档不存在或无权管理");
        }
        requireReadableKnowledgeBase(document.getKnowledgeBaseId(), user);
        return document;
    }

    /*
     * @Author: ys
     * @Date: 2026/7/27 星期一 23:14
     * @Desc: 知识库是否可读
     */
    private boolean isReadable(AiKnowledgeBase kb, Set<String> userRoles) {
        String configured = kb.getRoleCode();
        if (configured == null || configured.isBlank()) {
            // 这是显式“公开知识库”，不是权限异常时的降级。
            return true;
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .anyMatch(userRoles::contains);
    }
}
