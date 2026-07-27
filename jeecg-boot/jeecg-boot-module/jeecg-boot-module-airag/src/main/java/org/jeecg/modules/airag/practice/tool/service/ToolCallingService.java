package org.jeecg.modules.airag.practice.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.AuthorizationException;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.tool.entity.AiToolCallLog;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.entity.AiPendingToolCall;
import org.jeecg.modules.airag.practice.tool.handler.AbstractToolHandler;
import org.jeecg.modules.airag.practice.tool.ToolContext;
import org.jeecg.modules.airag.practice.tool.handler.ToolHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 工具调用服务
 *
 * 职责：
 * 1. 从数据库加载 active 工具定义，按当前用户权限过滤
 * 2. 构建 ToolSpecification（给模型看的"说明书"）
 * 3. 提供 ToolHandler + AiToolDefinition 映射，供 ToolChatService 手动执行工具
 * 4. 执行工具时自动设置上下文（用户、会话）并记录调用日志
 */
@Slf4j
@Service
public class ToolCallingService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private IAiToolDefinitionService aiToolDefinitionService;

    @Resource
    private IAiToolRolePermissionService aiToolRolePermissionService;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAiToolCallLogService aiToolCallLogService;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    // ======================== 内部数据结构 ========================

    /**
     * 工具加载结果，包含模型需要的 Specification 列表和执行时需要的 Handler/Definition 映射
     */
    @Data
    @AllArgsConstructor
    public static class ToolBundle {
        /** 给模型看的工具说明书列表 */
        private List<ToolSpecification> specifications;
        /** 工具编码 → Handler 映射，用于执行 */
        private Map<String, ToolHandler> handlerMap;
        /** 工具编码 → 工具定义映射，用于获取元信息（名称、ID 等） */
        private Map<String, AiToolDefinition> defMap;

        public boolean isEmpty() {
            return specifications == null || specifications.isEmpty();
        }
    }

    // ======================== 核心方法 ========================

    /**
     * 构建当前用户可用的工具集合
     */
    public ToolBundle buildToolMap() {
        LoginUser currentUser = null;
        try {
            currentUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        } catch (Exception ignored) {}
        return buildToolMap(currentUser);
    }

    /**
     * 构建工具集合（支持外部传入用户，用于 SSE 线程池等场景）
     */
    public ToolBundle buildToolMap(LoginUser currentUser) {
        List<AiToolDefinition> activeTools = aiToolDefinitionService.listActiveTools();
        if (activeTools.isEmpty()) {
            return new ToolBundle(Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        }
        if (currentUser == null) {
            log.warn("[ToolCalling] 未登录，拒绝加载工具");
            return new ToolBundle(List.of(), Map.of(), Map.of());
        }
        List<String> userRoles = getUserRoleCodes(currentUser);
        if (userRoles.isEmpty()) {
            log.warn("[ToolCalling] userId={} 无角色，拒绝加载工具", currentUser.getId());
            return new ToolBundle(List.of(), Map.of(), Map.of());
        }
        Set<String> toolIdSet = new HashSet<>(
                aiToolRolePermissionService.getPermittedToolIds(userRoles));

        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolHandler> handlerMap = new LinkedHashMap<>();
        Map<String, AiToolDefinition> defMap = new LinkedHashMap<>();

        for (AiToolDefinition def : activeTools) {
            if (!toolIdSet.contains(def.getId())) {
                log.debug("[ToolCalling] 工具 {} 对当前用户不可用，跳过", def.getToolCode());
                continue;
            }
            try {
                ToolSpecification spec = buildSpec(def);
                ToolHandler handler = (ToolHandler) applicationContext.getBean(def.getHandlerRef());

                specs.add(spec);
                handlerMap.put(def.getToolCode(), handler);
                defMap.put(def.getToolCode(), def);

                log.info("加载工具: {} ({}) → {}", def.getToolCode(), def.getToolName(), def.getHandlerRef());
            } catch (Exception e) {
                log.error("加载工具失败: {} - {}", def.getToolCode(), e.getMessage(), e);
            }
        }

        log.info("[ToolCalling] 当前用户可用工具数: {}/{}", specs.size(), activeTools.size());
        return new ToolBundle(specs, handlerMap, defMap);
    }


    private void assertToolPermission(AiToolDefinition def, LoginUser user) {
        if (def == null || user == null || !"active".equals(def.getStatus())) {
            throw new AuthorizationException("工具不存在或无权调用");
        }
        List<String> roles = getUserRoleCodes(user);
        if (roles.isEmpty()) {
            throw new AuthorizationException("工具不存在或无权调用");
        }
        List<String> permitted = aiToolRolePermissionService.getPermittedToolIds(roles);
        if (!permitted.contains(def.getId())) {
            throw new AuthorizationException("工具不存在或无权调用");
        }
    }

    /**
     * 按工具编码重新读取启用中的定义，并校验当前用户是否仍有执行权限。
     */
    public AiToolDefinition assertExecutable(String toolCode, LoginUser user) {
        if (toolCode == null || toolCode.isBlank()) {
            throw new IllegalArgumentException("toolCode 不能为空");
        }
        AiToolDefinition def = aiToolDefinitionService.lambdaQuery()
                .eq(AiToolDefinition::getToolCode, toolCode)
                .eq(AiToolDefinition::getStatus, "active")
                .one();
        assertToolPermission(def, user);
        if (!"JAVA_BEAN".equals(def.getEndpointType())) {
            throw new AuthorizationException("不支持的工具端点类型");
        }
        return def;
    }

    /**
     * 在创建确认单前调用 Handler 的只校验入口，不触发真实业务操作。
     */
    public void validateArguments(String toolCode, String argsJson, LoginUser user) {
        AiToolDefinition def = assertExecutable(toolCode, user);
        ToolHandler handler = applicationContext.getBean(def.getHandlerRef(), ToolHandler.class);
        List<String> errors = handler.validateArguments(argsJson);
        if (errors != null && !errors.isEmpty()) {
            throw new IllegalArgumentException("工具参数不合法: " + String.join("; ", errors));
        }
    }

    /**
     * 执行工具（支持外部显式传入用户，如评测引擎传入专有 admin 账号；无显式用户且未登录时抛未登录异常）
     */
    public String executeTool(String toolCode, ToolHandler handler, AiToolDefinition def,
                               String argsJson, String sessionId, String messageId, LoginUser currentUser) {
        return executeToolByCode(toolCode, argsJson, sessionId, messageId, currentUser, null);
    }

    /**
     * 统一工具执行入口：重读定义、二次授权、设置用户上下文并写入审计日志。
     */
    public String executeToolByCode(String toolCode, String argsJson, String sessionId,
                                    String messageId, LoginUser currentUser, String pendingCallId) {
        if (currentUser == null) {
            try {
                currentUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
            } catch (Exception ignored) {}
        }
        if (currentUser == null) {
            throw new RuntimeException("用户未登录，无法执行工具!");
        }
        AiToolDefinition def = assertExecutable(toolCode, currentUser);
        ToolHandler handler = applicationContext.getBean(def.getHandlerRef(), ToolHandler.class);
        ToolContext ctx = new ToolContext(currentUser, sessionId, messageId);
        AbstractToolHandler.setContext(ctx);

        long start = System.currentTimeMillis();
        String status = "success";
        String errorMsg = null;
        String result = null;

        try {
            result = handler.execute(argsJson);
            if (isErrorResult(result)) {
                status = "error";
                errorMsg = readError(result);
            }
        } catch (Exception e) {
            status = "error";
            errorMsg = "工具执行异常";
            result = "{\"error\":\"工具执行失败\"}";
            log.error("[{}] 工具执行异常: {}", toolCode, e.getMessage(), e);
        } finally {
            AbstractToolHandler.clearContext();
        }

        long duration = System.currentTimeMillis() - start;
        logCall(def, argsJson, result, duration, status, errorMsg,
                sessionId, messageId, pendingCallId, currentUser);

        return result;
    }

    /**
     * 通过解析 JSON 顶层状态判断工具是否失败，避免使用字符串包含关系误判。
     */
    public boolean isErrorResult(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            return node.hasNonNull("error")
                    || (node.has("success") && !node.path("success").asBoolean(true));
        } catch (Exception e) {
            return false;
        }
    }

    private String readError(String result) {
        try {
            return truncate(objectMapper.readTree(result).path("error").asText("工具执行失败"), 1000);
        } catch (Exception e) {
            return "工具执行失败";
        }
    }

    // ======================== 内部方法 ========================

    /**
     * 获取当前用户的角色编码列表
     */
    private List<String> getCurrentUserRoleCodes() {
        LoginUser user = null;
        try {
            user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        } catch (Exception ignored) {}
        return getUserRoleCodes(user);
    }

    private List<String> getUserRoleCodes(LoginUser user) {
        try {
            if (user == null) return List.of();
            String roles = user.getRoleCode();
            if (roles != null && !roles.isBlank()) {
                return Arrays.asList(roles.split(","));
            }
        } catch (Exception e) {
            log.warn("获取当前用户角色失败", e);
        }
        return List.of();
    }

    /**
     * 构建单个工具的 ToolSpecification
     */
    public ToolSpecification buildSpec(AiToolDefinition activeTool) {
        JsonObjectSchema jsonObjectSchema = parseSchema(activeTool.getParametersSchema());
        return ToolSpecification.builder()
                .name(activeTool.getToolCode())
                .description(activeTool.getDescription())
                .parameters(jsonObjectSchema)
                .build();
    }

    /**
     * 解析 JSON Schema 字符串 → LangChain4j JsonObjectSchema
     */
    JsonObjectSchema parseSchema(String schemaJson) {
        try {
            if (schemaJson == null || schemaJson.isBlank()) {
                return JsonObjectSchema.builder().build();
            }

            JsonNode root = objectMapper.readTree(schemaJson);
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

            JsonNode properties = root.get("properties");
            if (properties != null && properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    addProperty(builder, entry.getKey(), entry.getValue());
                }
            }

            JsonNode required = root.get("required");
            if (required != null && required.isArray()) {
                List<String> requiredFields = new ArrayList<>();
                required.forEach(n -> requiredFields.add(n.asText()));
                builder.required(requiredFields);
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("解析 parametersSchema 失败，使用空 schema: {}", e.getMessage());
            return JsonObjectSchema.builder().build();
        }
    }

    private void addProperty(JsonObjectSchema.Builder builder, String name, JsonNode propDef) {
        String type = propDef.has("type") ? propDef.get("type").asText() : "string";
        String desc = propDef.has("description") ? propDef.get("description").asText() : null;

        if (propDef.has("enum")) {
            List<String> enumValues = new ArrayList<>();
            propDef.get("enum").forEach(n -> enumValues.add(n.asText()));
            builder.addEnumProperty(name, enumValues, desc);
            return;
        }

        switch (type) {
            case "integer":
                builder.addIntegerProperty(name, desc);
                break;
            case "number":
                builder.addNumberProperty(name, desc);
                break;
            case "boolean":
                builder.addBooleanProperty(name, desc);
                break;
            case "string":
            default:
                builder.addStringProperty(name, desc);
                break;
        }
    }

    /**
     * 记录工具调用日志
     */
    private void logCall(AiToolDefinition def, String argsJson, String result,
                         long duration, String status, String errorMsg, String sessionId,
                         String messageId, String pendingCallId, LoginUser user) {
        try {
            AiToolCallLog callLog = new AiToolCallLog();
            callLog.setSessionId(sessionId);
            callLog.setMessageId(messageId);
            callLog.setPendingCallId(pendingCallId);
            callLog.setToolCode(def.getToolCode());
            callLog.setToolName(def.getToolName());
            callLog.setInputParams(truncate(argsJson, 2000));
            callLog.setOutputResult(truncate(result, 4000));
            callLog.setStatus(status);
            callLog.setErrorMsg(truncate(errorMsg, 1000));
            callLog.setDurationMs((int) Math.min(duration, Integer.MAX_VALUE));
            callLog.setModelName(modelName);
            callLog.setCreateBy(user.getId());
            callLog.setCreateTime(new Date());
            aiToolCallLogService.save(callLog);
        } catch (Exception e) {
            log.warn("记录工具调用日志失败: {}", e.getMessage());
        }
    }

    /** 记录写工具等待用户确认的审计事件。 */
    public void auditPending(AiPendingToolCall pending, LoginUser user) {
        AiToolDefinition def = assertExecutable(pending.getToolCode(), user);
        logCall(def, pending.getArgumentsJson(), null, 0, "pending_confirm", null,
                pending.getSessionId(), pending.getMessageId(), pending.getId(), user);
    }

    /** 将当前用户对应确认单的待确认审计记录更新为已取消。 */
    public void auditCancelled(String pendingCallId, LoginUser user) {
        AiToolCallLog audit = aiToolCallLogService.lambdaQuery()
                .eq(AiToolCallLog::getPendingCallId, pendingCallId)
                .eq(AiToolCallLog::getCreateBy, user.getId())
                .eq(AiToolCallLog::getStatus, "pending_confirm")
                .one();
        if (audit != null) {
            audit.setStatus("cancelled");
            aiToolCallLogService.updateById(audit);
        }
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}
