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
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.tool.entity.AiToolCallLog;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.handler.AbstractToolHandler;
import org.jeecg.modules.airag.practice.tool.ToolContext;
import org.jeecg.modules.airag.practice.tool.handler.ToolHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

        List<String> userRoles = getUserRoleCodes(currentUser);
        Set<String> toolIdSet;
        if (userRoles.isEmpty()) {
            log.info("[ToolCalling] 无登录用户，跳过权限过滤，加载全部 active 工具");
            toolIdSet = activeTools.stream().map(AiToolDefinition::getId).collect(Collectors.toSet());
        } else {
            List<String> toolIds = aiToolRolePermissionService.getPermittedToolIds(userRoles);
            toolIdSet = new HashSet<>(toolIds);
        }

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

    /**
     * 执行单个工具并记录日志（供 ToolChatService 手动调用）
     *
     * @param toolCode  工具编码
     * @param handler   工具处理器
     * @param def       工具定义
     * @param argsJson  参数 JSON 字符串
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @return 工具执行结果 JSON 字符串
     */
    public String executeTool(String toolCode, ToolHandler handler, AiToolDefinition def,
                              String argsJson, String sessionId, String messageId) {
        LoginUser currentUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        return executeTool(toolCode, handler, def, argsJson, sessionId, messageId, currentUser);
    }

    /**
     * 执行工具（支持外部传入用户，用于 SSE 线程池等无法通过 SecurityUtils 获取用户的场景）
     */
    public String executeTool(String toolCode, ToolHandler handler, AiToolDefinition def,
                              String argsJson, String sessionId, String messageId, LoginUser currentUser) {
        ToolContext ctx = new ToolContext(currentUser, sessionId, messageId);
        AbstractToolHandler.setContext(ctx);

        long start = System.currentTimeMillis();
        String status = "success";
        String errorMsg = null;
        String result = null;

        try {
            result = handler.execute(argsJson);
        } catch (Exception e) {
            status = "error";
            errorMsg = e.getMessage();
            result = "{\"error\": \"工具执行异常: " + e.getMessage() + "\"}";
            log.error("[{}] 工具执行异常: {}", toolCode, e.getMessage(), e);
        } finally {
            AbstractToolHandler.clearContext();
        }

        long duration = System.currentTimeMillis() - start;
        logCall(def, argsJson, result, duration, status, errorMsg, sessionId);

        return result;
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
                         long duration, String status, String errorMsg, String sessionId) {
        try {
            AiToolCallLog callLog = new AiToolCallLog();
            callLog.setSessionId(sessionId);
            callLog.setToolCode(def.getToolCode());
            callLog.setToolName(def.getToolName());
            callLog.setInputParams(argsJson);
            callLog.setOutputResult(result);
            callLog.setStatus(status);
            callLog.setErrorMsg(errorMsg);
            callLog.setDurationMs((int) duration);
            callLog.setModelName(modelName);
            callLog.setCreateTime(new Date());
            aiToolCallLogService.save(callLog);
        } catch (Exception e) {
            log.warn("记录工具调用日志失败: {}", e.getMessage());
        }
    }
}
