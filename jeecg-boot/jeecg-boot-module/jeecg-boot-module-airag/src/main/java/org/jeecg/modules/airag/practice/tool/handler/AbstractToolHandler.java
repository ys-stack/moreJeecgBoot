package org.jeecg.modules.airag.practice.tool.handler;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.tool.ToolContext;

import java.util.List;

/**
 * 工具处理器抽象基类
 *
 * 模板方法模式：
 *   execute(json)  →  validate(args)  →  doExecute(args)
 *                        ↓ 失败
 *                   返回错误 JSON，不执行
 *
 * 子类只需实现：
 *   1. validate(JSONObject args) — 返回错误列表，空=通过
 *   2. doExecute(JSONObject args) — 实际业务逻辑
 */
@Slf4j
public abstract class AbstractToolHandler implements ToolHandler{

    // ==================== 工具执行上下文（ThreadLocal） ====================
    private static final ThreadLocal<ToolContext> CONTEXT_HOLDER = new ThreadLocal<>();

    @Override
    public String execute(String argumentsJson) {
        // ① 解析参数
        JSONObject args;
        try {
            args = JSON.parseObject(argumentsJson);
        } catch (Exception e) {
            log.warn("[{}] 参数 JSON 解析失败: {}", getToolCode(), argumentsJson);
            return errorResult("参数格式错误，期望 JSON 对象");
        }
        // ② 参数校验
        List<String> errors = validate(args);
        if (ObjectUtil.isNotEmpty(errors)) {
            log.warn("[{}] 参数校验失败: {}", getToolCode(), errors);
            return errorResult("参数校验失败", errors);
        }
        try{
            //执行逻辑
            return execute(args);
        }catch (Exception e){
            log.error("[{}] 执行异常", getToolCode(), e);
            return errorResult("工具执行异常：" + e.getMessage());
        }
    }
    /**
     * 参数校验 —— 子类实现
     * 返回空列表表示通过，非空列表表示有错误
     */
    protected abstract List<String> validate(JSONObject args);
    /**
     * 实际业务逻辑 —— 子类实现
     * 调用此方法时参数已经过校验，可直接使用
     */
    protected abstract String execute(JSONObject args);
    /**
     * 工具编码 —— 子类实现，用于日志标识
     */
    protected abstract String getToolCode();

    // ==================== 错误结果构造 ====================

    protected String errorResult(String msg) {
        JSONObject json = new JSONObject();
        json.put("error", msg);
        return json.toJSONString();
    }

    protected String errorResult(String msg, List<String> details) {
        JSONObject json = new JSONObject();
        json.put("error", msg);
        json.put("details", details);
        return json.toJSONString();
    }

    /** ToolCallingService 在调 Handler 前调用 */
    public static void setContext(ToolContext ctx) {
        CONTEXT_HOLDER.set(ctx);
    }

    /** ToolCallingService 在调完 Handler 后调用（finally 里） */
    public static void clearContext() {
        CONTEXT_HOLDER.remove();
    }

    /** 子类 Handler 内部调用，获取当前用户 */
    protected ToolContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    /** 便捷方法：直接拿当前用户，可能返回 null */
    protected LoginUser getCurrentUser() {
        ToolContext ctx = getContext();
        return ctx != null ? ctx.getCurrentUser() : null;
    }

    /** 便捷方法：判断当前用户是否是管理员 */
    protected boolean isCurrentUserAdmin() {
        LoginUser user = getCurrentUser();
        if (user == null) return false;
        // 方式1：看用户名
        if ("admin".equals(user.getUsername())) return true;
        // 方式2：看角色字符串里有没有 admin
        String roles = user.getRoleCode();
        return roles != null && roles.contains("admin");
    }
}
