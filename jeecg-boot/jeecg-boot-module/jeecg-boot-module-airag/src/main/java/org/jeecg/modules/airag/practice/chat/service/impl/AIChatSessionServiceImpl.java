package org.jeecg.modules.airag.practice.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatSessionMapper;
import org.jeecg.modules.airag.practice.chat.service.IAIChatSessionService;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class AIChatSessionServiceImpl implements IAIChatSessionService {

    @Resource
    private AiChatSessionMapper aiChatSessionMapper;

    /*
     * @Author: ys
     * @Date: 2026/7/15 14:22
     * @DESC: 校验session权限
     */
    @Override
    public boolean verifySessionPermission(String sessionId, String userId) {
        Assert.hasText(sessionId, "sessionId不能为空");
        String actualUserId = userId;
        if (actualUserId == null || actualUserId.isBlank()) {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            if (!(principal instanceof LoginUser loginUser)) {
                throw new IllegalStateException("用户未登录");
            }
            actualUserId = loginUser.getId();
        }
        if (actualUserId == null || actualUserId.isBlank()) {
            throw new IllegalStateException("当前登录用户ID为空");
        }
        Long count = aiChatSessionMapper.selectCount(
                new LambdaQueryWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, actualUserId)
                        .eq(AiChatSession::getStatus, "active")
        );
        return count != null && count > 0;
    }
}
