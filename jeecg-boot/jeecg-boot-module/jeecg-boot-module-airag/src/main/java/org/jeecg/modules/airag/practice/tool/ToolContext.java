package org.jeecg.modules.airag.practice.tool;

import lombok.Data;
import org.jeecg.common.system.vo.LoginUser;

/**
 * 工具执行上下文
 * 在 buildExecutor 时创建，执行 Handler 前通过 ThreadLocal 设置，
 * Handler 执行完后清除。这样 Handler 内部可以通过 ToolContext.get() 获取当前用户信息。
 */

@Data
public class ToolContext {
    private final LoginUser currentUser;
    private final String sessionId;
    private final String messageId;

    public ToolContext(LoginUser currentUser, String sessionId, String messageId) {
        this.currentUser = currentUser;
        this.sessionId = sessionId;
        this.messageId = messageId;
    }
}
