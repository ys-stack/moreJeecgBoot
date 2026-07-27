package org.jeecg.modules.airag.practice.tool.service;

import jakarta.annotation.Resource;
import org.apache.shiro.authz.AuthorizationException;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.tool.entity.AiPendingToolCall;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.mapper.AiPendingToolCallMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

/**
 * 写工具服务端确认服务。
 * 负责创建确认单、绑定原始参数、确认执行、取消及防重复执行。
 */
@Service
public class PendingToolCallService {

    /** 确认单数据访问。 */
    @Resource
    private AiPendingToolCallMapper pendingMapper;

    /** 工具授权、执行与审计服务。 */
    @Resource
    private ToolCallingService toolCallingService;

    /**
     * 保存模型提出的写工具调用。保存前再次校验工具权限和参数，但不执行真实写操作。
     */
    @Transactional(rollbackFor = Exception.class)
    public AiPendingToolCall create(AiToolDefinition definition, String argumentsJson,
                                    String sessionId, String messageId, LoginUser user) {
        AiToolDefinition activeDefinition = toolCallingService.assertExecutable(
                definition.getToolCode(), user);
        if (!Integer.valueOf(1).equals(activeDefinition.getRequireConfirm())) {
            throw new IllegalArgumentException("该工具不需要写操作确认");
        }
        toolCallingService.validateArguments(activeDefinition.getToolCode(), argumentsJson, user);

        Date now = new Date();
        AiPendingToolCall pending = new AiPendingToolCall()
                .setSessionId(sessionId)
                .setMessageId(messageId)
                .setToolId(activeDefinition.getId())
                .setToolCode(activeDefinition.getToolCode())
                .setArgumentsJson(argumentsJson)
                .setArgumentsHash(sha256(argumentsJson))
                .setUserId(user.getId())
                .setStatus("PENDING")
                .setExpiresAt(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .setCreateTime(now)
                .setUpdateTime(now);
        pendingMapper.insert(pending);
        toolCallingService.auditPending(pending, user);
        return pending;
    }

    /**
     * 执行用户确认的写操作。通过行锁、用户绑定、过期时间和参数摘要防止越权与重放。
     */
    @Transactional(rollbackFor = Exception.class)
    public String confirm(String pendingCallId, LoginUser user) {
        AiPendingToolCall pending = pendingMapper.selectForUpdate(pendingCallId);
        if (pending == null || user == null || !user.getId().equals(pending.getUserId())) {
            throw new AuthorizationException("确认单不存在或无权操作");
        }
        if (!"PENDING".equals(pending.getStatus())) {
            throw new IllegalStateException("确认单已处理，不能重复执行");
        }
        if (!pending.getExpiresAt().after(new Date())) {
            throw new IllegalStateException("确认单已过期");
        }
        if (!sha256(pending.getArgumentsJson()).equals(pending.getArgumentsHash())) {
            throw new IllegalStateException("确认单参数完整性校验失败");
        }

        pending.setStatus("EXECUTING").setUpdateTime(new Date());
        pendingMapper.updateById(pending);

        String result = toolCallingService.executeToolByCode(
                pending.getToolCode(), pending.getArgumentsJson(), pending.getSessionId(),
                pending.getMessageId(), user, pending.getId());

        boolean failed = toolCallingService.isErrorResult(result);
        pending.setStatus(failed ? "FAILED" : "EXECUTED")
                .setOutputResult(truncate(result, 4000))
                .setErrorMsg(failed ? "工具执行失败" : null)
                .setExecutedAt(new Date())
                .setUpdateTime(new Date());
        pendingMapper.updateById(pending);
        return result;
    }

    /**
     * 取消当前用户尚未执行且未过期的确认单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String pendingCallId, LoginUser user) {
        if (user == null || pendingMapper.cancel(pendingCallId, user.getId()) != 1) {
            throw new AuthorizationException("确认单不存在、已处理或已过期");
        }
        toolCallingService.auditCancelled(pendingCallId, user);
    }

    /** 计算确认参数的 SHA-256 十六进制摘要。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按数据库字段上限截断审计结果。 */
    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}
