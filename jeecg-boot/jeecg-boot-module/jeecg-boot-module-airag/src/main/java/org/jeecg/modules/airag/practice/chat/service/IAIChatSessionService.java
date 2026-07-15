package org.jeecg.modules.airag.practice.chat.service;

public interface IAIChatSessionService {

    /** 校验session权限 **/
    boolean verifySessionPermission(String sessionId, String userId);
}
