package org.jeecg.modules.airag.practice.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.annotation.Resource;
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
    private ToolCallingDispatcher toolCallingDispatcher;

    @Resource
    private IAiToolCallLogService aiToolCallLogService;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    /*
     * @Author: ys
     * @Date: 2026/6/25 15:33
     * @DESC: 获取当前用户的角色编码列表
     */
    private List<String> getCurrentUserRoleCodes() {
        try {
            LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
            if (user == null) return List.of();

            // JeecgBoot 的 LoginUser.roles 通常是逗号分隔的字符串如 "admin,user"
            // 也可能是 List<SysRoleModel>，你检查一下你版本的源码
            String roles = user.getRoleCode();
            if (roles != null && !roles.isBlank()) {
                return Arrays.asList(roles.split(","));
            }
        } catch (Exception e) {
            log.warn("获取当前用户角色失败", e);
        }
        return List.of();
    }

    /*
     * @Author: ys
     * @Date: 2026/6/25 15:36
     * @DESC: 构建当前用户可用的工具映射，这个方法在 generate 循环开始前调用。返回的 Map 里只包含当前用户有权限的工具
     */
    public Map<ToolSpecification, ToolExecutor> buildToolMap(String sessionId, String messageId) {
        List<AiToolDefinition> activeTools = aiToolDefinitionService.listActiveTools();
        if (activeTools.isEmpty()) {
            return Collections.emptyMap();
        }
        //获取当前用户的角色
        List<String> userRoles  = getCurrentUserRoleCodes();
        //查询这些角色有权限的工具ID
        List<String> toolIds = aiToolRolePermissionService.getPermittedToolIds(userRoles);
        Set<String> toolIdSet = new HashSet<>(toolIds);
        //过滤 + 构建
        LoginUser currentUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();

        for (AiToolDefinition activeTool : activeTools) {
            if (!toolIdSet.contains(activeTool.getId())) {
                log.debug("[ToolCalling] 工具 {} 对当前用户不可用，跳过", activeTool.getToolCode());
                continue;
            }
            ToolSpecification spec = buildSpec(activeTool);
            ToolExecutor executor = buildExecutor(activeTool, currentUser, sessionId, messageId);
            toolMap.put(spec, executor);
        }
        log.info("[ToolCalling] 当前用户可用工具数: {}/{}", toolMap.size(), activeTools.size());
        return toolMap;
    }

    /**
     * 加载当前用户有权限的 active 工具，返回 LoadedTools（兼容 ToolChatService 现有逻辑）
     *
     * 和 ToolCallingDispatcher.loadActiveTools() 的区别：
     *   - Dispatcher 加载全部 active 工具（无权限过滤）
     *   - 这里先按角色过滤，只返回当前用户有权限的工具
     *
     * @return LoadedTools（specifications + handlers + definitions），无权限工具已被过滤
     */
    public ToolCallingDispatcher.LoadedTools loadActiveToolsWithPermission() {
        List<AiToolDefinition> activeTools = aiToolDefinitionService.listActiveTools();
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolHandler> handlerMap = new HashMap<>();
        Map<String, AiToolDefinition> defMap = new HashMap<>();

        if (activeTools.isEmpty()) {
            return new ToolCallingDispatcher.LoadedTools(specs, handlerMap, defMap);
        }

        // 权限过滤
        List<String> userRoles = getCurrentUserRoleCodes();
        List<String> toolIds = aiToolRolePermissionService.getPermittedToolIds(userRoles);
        Set<String> toolIdSet = new HashSet<>(toolIds);
        LoginUser currentUser = null;
        try {
            currentUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        } catch (Exception ignored) {}

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
            } catch (Exception e) {
                log.error("加载工具失败: {} - {}", def.getToolCode(), e.getMessage(), e);
            }
        }

        log.info("[ToolCalling] 权限过滤后加载 {} 个工具（总共 {} 个）", specs.size(), activeTools.size());
        return new ToolCallingDispatcher.LoadedTools(specs, handlerMap, defMap);
    }

    /**
     * 为一个工具定义构建执行器
     * @param activeTool    工具定义（从数据库读出来的）
     * @param currentUser 当前登录用户
     * @param sessionId  对话会话 ID
     * @param messageId  触发这次工具调用的用户消息 ID
     * @return LangChain4j 的 ToolExecutor，模型调工具时由框架自动调用
     */
    private ToolExecutor buildExecutor(AiToolDefinition activeTool, LoginUser currentUser, String sessionId, String messageId) {
        // 返回一个 lambda —— 这就是 ToolExecutor 的函数式实现
        // request 是模型发过来的调用请求，包含工具名和参数
        return (request,memoryId) -> {
            String argsJson = request.arguments().toString();
            //从 Spring 容器拿 Handler Bean
            String handlerRef = activeTool.getHandlerRef();
            ToolHandler handler = (ToolHandler) applicationContext.getBean(handlerRef);

            //设置上下文（用户信息、会话信息）
            ToolContext ctx = new ToolContext(currentUser, sessionId, messageId);
            AbstractToolHandler.setContext(ctx);

            long start = System.currentTimeMillis();
            try{
                //执行 Handler，拿到结果
                String result = handler.execute(request.arguments());
                long duration = System.currentTimeMillis() - start;
                logCall(activeTool, argsJson, result, duration, "success", null,sessionId);
                return result;
            }catch (Exception e){
                long duration = System.currentTimeMillis() - start;
                logCall(activeTool, argsJson, null, duration, "error", e.getMessage(),sessionId);
                return "{\"error\": \"工具执行异常: " + e.getMessage() + "\"}";
            }
            finally {
                //无论成功失败，必须清除 ThreadLocal，防止内存泄漏
                AbstractToolHandler.clearContext();
            }
        };
    }

    /*
     * @Author: ys
     * @Date: 2026/6/25 17:37
     * @DESC: 记录工具调用
     */
    private void logCall(AiToolDefinition def, String argsJson, String result, long duration, String status, String errorMsg, String sessionId) {
        try{
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

    /*
     * @Author: ys
     * @Date: 2026/6/25 16:15
     * @DESC: 把系统定义的工具转成ToolSpecification
     */
    public ToolSpecification buildSpec(AiToolDefinition activeTool) {
        // 1. 把数据库里的 JSON Schema 字符串解析成 LangChain4j 的 JsonObjectSchema
        JsonObjectSchema jsonObjectSchema = parseSchema(activeTool.getParametersSchema());
        return ToolSpecification.builder()
                .name(activeTool.getToolCode())       // 工具编码，如 "queryOrder"
                .description(activeTool.getDescription()) // 工具描述，帮助模型决定何时调用
                .parameters(jsonObjectSchema)
                .build();
    }

    /**
     * 解析数据库中的 JSON Schema 字符串 → LangChain4j 的 JsonObjectSchema
     *
     * 数据库里的格式示例：
     * {
     *   "type": "object",
     *   "properties": {
     *     "orderCode": { "type": "string", "description": "订单号" }
     *   },
     *   "required": ["orderCode"]
     * }
     *
     * 需要转成 LangChain4j 的 JsonObjectSchema（通过 Builder 手动构建）。
     */
    JsonObjectSchema parseSchema(String schemaJson) {
        try {
            if (schemaJson == null || schemaJson.isBlank()) {
                return JsonObjectSchema.builder().build();
            }

            JsonNode root = objectMapper.readTree(schemaJson);
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

            // 解析 properties
            JsonNode properties = root.get("properties");
            if (properties != null && properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String propName = entry.getKey();
                    JsonNode propDef = entry.getValue();
                    addProperty(builder, propName, propDef);
                }
            }

            // 解析 required
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

    /**
     * 根据 JSON Schema 中单个 property 的 type，调对应的 Builder 方法
     *
     * 支持的类型：
     * - "string"  → addStringProperty(name, description)
     * - "integer" → addIntegerProperty(name, description)
     * - "number"  → addNumberProperty(name, description)
     * - "boolean" → addBooleanProperty(name, description)
     * - 带 "enum" 的 string → addEnumProperty(name, enumValues, description)
     */
    private void addProperty(JsonObjectSchema.Builder builder, String name, JsonNode propDef) {
        String type = propDef.has("type") ? propDef.get("type").asText() : "string";
        String desc = propDef.has("description") ? propDef.get("description").asText() : null;

        // 检查是否有 enum 约束（如 ticketType: ["bug", "feature", "task", "question"]）
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

}
