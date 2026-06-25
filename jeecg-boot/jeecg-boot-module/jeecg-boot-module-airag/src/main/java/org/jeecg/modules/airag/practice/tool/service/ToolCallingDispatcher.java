package org.jeecg.modules.airag.practice.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.handler.ToolHandler;
import org.jeecg.modules.airag.practice.tool.entity.AiToolCallLog;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Tool Calling 调度器
 *
 * 职责：
 * 1. 从数据库加载 active 工具定义，转成 LangChain4j 的 ToolSpecification（给模型看的"说明书"）
 * 2. 为每个工具绑定 ToolExecutor（模型决定调用时触发的执行器）
 * 3. 在执行过程中自动记录调用日志到 ai_tool_call_log 表
 *
 * 核心转换流程：
 *   ai_tool_definition 表记录
 *     → ToolSpecification（name + description + parametersSchema）
 *     → ToolExecutor（通过 handlerRef 从 Spring 容器找 Bean）
 *
 * 这样后续新增工具只需要：
 *   1. 写一个 ToolHandler 实现类（如 XxxToolHandler）
 *   2. 在 ai_tool_definition 表插一条记录（handlerRef 指向那个 Bean 名）
 *   Dispatcher 会自动加载，不需要改任何 Java 代码。
 */
@Slf4j
@Service
public class ToolCallingDispatcher {

    @Resource
    private IAiToolDefinitionService toolDefService;

    @Resource
    private IAiToolCallLogService toolCallLogService;

    @Resource
    private ApplicationContext applicationContext;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ======================== 对外暴露的核心方法 ========================

    /**
     * 加载所有 active 工具，返回 LangChain4j 需要的两个东西：
     * - List<ToolSpecification>：工具的"说明书"，发给模型让它知道有哪些工具可用
     * - Map<String, ToolHandler>：工具编码 → Handler 的映射，用于执行时查找
     *
     * 为什么返回 Handler 而不是 ToolExecutor？
     * 因为 ToolChatService 需要手动控制循环（记录每一步的中间状态），
     * 所以不用 LangChain4j 的自动 ToolExecutor 机制，而是自己在循环里调 Handler。
     */
    public LoadedTools loadActiveTools() {
        List<AiToolDefinition> activeTools = toolDefService.listActiveTools();
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolHandler> handlerMap = new HashMap<>();
        Map<String, AiToolDefinition> defMap = new HashMap<>();

        for (AiToolDefinition def : activeTools) {
            try {
                // 1. 把数据库里的 JSON Schema 字符串解析成 LangChain4j 的 JsonObjectSchema
                JsonObjectSchema schema = parseSchema(def.getParametersSchema());

                // 2. 构建 ToolSpecification（模型看到的"工具说明书"）
                ToolSpecification spec = ToolSpecification.builder()
                        .name(def.getToolCode())       // 工具编码，如 "queryOrder"
                        .description(def.getDescription()) // 工具描述，帮助模型决定何时调用
                        .parameters(schema)
                        .build();

                // 3. 根据 handlerRef 从 Spring 容器拿到对应的 ToolHandler Bean
                //    例如 handlerRef="orderToolHandler" → applicationContext.getBean("orderToolHandler")
                ToolHandler handler = (ToolHandler) applicationContext.getBean(def.getHandlerRef());

                specs.add(spec);
                handlerMap.put(def.getToolCode(), handler);
                defMap.put(def.getToolCode(), def);

                log.info("加载工具: {} ({}) → {}", def.getToolCode(), def.getToolName(), def.getHandlerRef());
            } catch (Exception e) {
                log.error("加载工具失败: {} - {}", def.getToolCode(), e.getMessage(), e);
            }
        }

        log.info("共加载 {} 个 active 工具", specs.size());
        return new LoadedTools(specs, handlerMap, defMap);
    }

    /**
     * 执行单个工具并记录日志
     *
     * ToolChatService 在循环中检测到模型要调工具时，调用此方法。
     * 它会：调 Handler → 记录 ai_tool_call_log → 返回结果字符串
     */
    public String executeTool(ToolExecutionRequest request, AiToolDefinition def,
                              ToolHandler handler, String sessionId) {
        long start = System.currentTimeMillis();
        String status = "success";
        String errorMsg = null;
        String output = null;

        try {
            output = handler.execute(request.arguments());
        } catch (Exception e) {
            status = "error";
            errorMsg = e.getMessage();
            output = "{\"error\": \"" + e.getMessage() + "\"}";
            log.error("[{}] 工具执行异常: {}", def.getToolCode(), e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - start;

        // 记录到 ai_tool_call_log 表
        saveCallLog(def, request, output, status, errorMsg, duration, sessionId);

        return output;
    }

    // ======================== 内部方法 ========================

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

    /**
     * 记录工具调用日志到 ai_tool_call_log 表
     */
    private void saveCallLog(AiToolDefinition def, ToolExecutionRequest request,
                             String output, String status, String errorMsg,
                             long duration, String sessionId) {
        try {
            AiToolCallLog callLog = new AiToolCallLog();
            callLog.setSessionId(sessionId);
            callLog.setToolCode(def.getToolCode());
            callLog.setToolName(def.getToolName());
            callLog.setInputParams(request.arguments());
            callLog.setOutputResult(output);
            callLog.setStatus(status);
            callLog.setErrorMsg(errorMsg);
            callLog.setDurationMs((int) duration);
            callLog.setModelName(modelName);
            callLog.setCreateTime(new Date());
            toolCallLogService.save(callLog);
        } catch (Exception e) {
            log.warn("记录工具调用日志失败: {}", e.getMessage());
        }
    }


}
