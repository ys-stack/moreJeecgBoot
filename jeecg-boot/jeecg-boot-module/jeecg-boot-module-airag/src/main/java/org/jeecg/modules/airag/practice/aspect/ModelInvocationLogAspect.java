package org.jeecg.modules.airag.practice.aspect;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.jeecg.modules.airag.practice.aspect.annotation.ModelInvocationLog;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.IpUtils;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.modules.airag.practice.log.entity.AiModelCallLog;
import org.jeecg.modules.airag.practice.log.service.IAiModelCallLogService;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI 模型调用日志切面
 *
 * @Author: ys
 * @Date: 2026/6/13
 */
@Slf4j
@Aspect
@Component
public class ModelInvocationLogAspect {

    @Resource
    private IAiModelCallLogService aiModelCallLogService;

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /** 中文 token 估算系数：约 1.3 token/字 */
    private static final double ZH_TOKEN_RATIO = 1.3;
    /** 英文 token 估算系数：约 0.75 token/word */
    private static final double EN_TOKEN_RATIO = 0.75;
    /** 摘要截取最大长度 */
    private static final int SUMMARY_MAX_LEN = 500;

    @Pointcut("@annotation(org.jeecg.modules.airag.practice.aspect.annotation.ModelInvocationLog)")
    public void logPointCut() {
    }

    @Around("logPointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long beginTime = System.currentTimeMillis();
        String status = "success";
        String errorMsg = null;
        Object result = null;
        try {
            result = point.proceed();
            return result;
        } catch (Throwable e) {
            status = "fail";
            errorMsg = e.getMessage();
            throw e;
        } finally {
            long costMs = System.currentTimeMillis() - beginTime;
            try {
                saveLog(point, costMs, result, status, errorMsg);
            } catch (Exception ex) {
                log.warn("记录模型调用日志失败: {}", ex.getMessage());
            }
        }
    }

    private void saveLog(ProceedingJoinPoint point, long costMs, Object result, String status, String errorMsg) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        ModelInvocationLog ann = method.getAnnotation(ModelInvocationLog.class);
        if (ann == null) return;

        AiModelCallLog callLog = new AiModelCallLog();

        // 基础信息
        callLog.setBizType(ann.scene())
                .setDurationMs(costMs)
                .setStatus(status)
                .setErrorMsg(errorMsg != null && errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg)
                .setCreateTime(new Date());

        // 模型名：优先 SpEL 解析，其次从返回值反射取
        String modelName = resolveModelName(ann, point, result);
        callLog.setModelName(modelName);

        // 从返回值提取 requestId、model、content 等
        extractFromResult(result, callLog);

        // token：优先取模型返回的真实值，取不到再估算
        extractTokens(point, result, callLog);

        // 从方法参数中提取 promptCode / promptVersion
        if (ann.recordPromptInfo()) {
            extractPromptInfo(point, callLog);
        }

        // 用户信息
        if (ann.recordUserId()) {
            fillUserInfo(callLog);
        }

        // 请求信息（IP、接口路径）
        fillRequestInfo(callLog);

        // 描述
        if (!ann.description().isEmpty()) {
            callLog.setExtraData("{\"desc\":\"" + ann.description() + "\"}");
        }

        // 写库
        if (ann.async()) {
            CompletableFuture.runAsync(() -> safeSave(callLog));
        } else {
            safeSave(callLog);
        }
    }

    /** SpEL 解析 modelName，解析不到则从返回值反射取 model 字段 */
    private String resolveModelName(ModelInvocationLog ann, ProceedingJoinPoint point, Object result) {
        String expr = ann.modelName();
        if (!expr.isEmpty()) {
            try {
                EvaluationContext ctx = buildSpelContext(point);
                String resolved = PARSER.parseExpression(expr).getValue(ctx, String.class);
                if (resolved != null && !resolved.isEmpty()) return resolved;
            } catch (Exception e) {
                log.debug("SpEL 解析 modelName 失败: {}", e.getMessage());
            }
        }
        // 从返回值反射取 model 字段
        if (result != null) {
            Object val = getFieldValue(result, "model");
            if (val != null) return val.toString();
        }
        return "unknown";
    }

    /** 构建 SpEL 上下文（支持 #参数名 和 #this） */
    private EvaluationContext buildSpelContext(ProceedingJoinPoint point) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setRootObject(point.getTarget());
        ctx.setVariable("this", point.getTarget());
        MethodSignature sig = (MethodSignature) point.getSignature();
        String[] paramNames = NAME_DISCOVERER.getParameterNames(sig.getMethod());
        Object[] args = point.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        return ctx;
    }

    /** 从返回值反射提取 model、requestId、content */
    private void extractFromResult(Object result, AiModelCallLog callLog) {
        if (result == null) return;
        // requestId
        Object reqId = getFieldValue(result, "requestId");
        if (reqId != null) callLog.setRequestId(reqId.toString());
        // content → 截取作为 responseBody 摘要
        Object content = getFieldValue(result, "content");
        if (content != null) {
            String text = content.toString();
            callLog.setResponseBody(text.length() > SUMMARY_MAX_LEN ? text.substring(0, SUMMARY_MAX_LEN) : text);
        }
    }

    /**
     * Token 提取策略：
     * 1. 优先从返回值取真实 token（promptTokens/completionTokens 字段）
     *    → 真实项目中，模型 API 的 response 都会返回 usage 对象，
     *      LangChain4j 封装在 ChatResponse.tokenUsage() 里，
     *      服务层调用后应该把 inputTokenCount/outputTokenCount 透传到返回值中
     * 2. 取不到再走估算（中文 ×1.3、英文 ×0.75/word）
     */
    private void extractTokens(ProceedingJoinPoint point, Object result, AiModelCallLog callLog) {
        Integer promptTokens = null;
        Integer completionTokens = null;

        // 尝试从返回值取真实 token 数
        if (result != null) {
            Object pt = getFieldValue(result, "promptTokens");
            Object ct = getFieldValue(result, "completionTokens");
            if (pt instanceof Integer p) promptTokens = p;
            else if (pt instanceof Long p) promptTokens = p.intValue();
            if (ct instanceof Integer c) completionTokens = c;
            else if (ct instanceof Long c) completionTokens = c.intValue();
        }

        // 取不到就估算
        if (promptTokens == null) {
            int estimated = 0;
            for (Object arg : point.getArgs()) {
                if (arg instanceof String s) {
                    estimated += estimateTokenCount(s);
                } else if (arg != null) {
                    Object msg = getFieldValue(arg, "message");
                    if (msg != null) estimated += estimateTokenCount(msg.toString());
                    Object sys = getFieldValue(arg, "systemPrompt");
                    if (sys != null) estimated += estimateTokenCount(sys.toString());
                }
            }
            promptTokens = estimated;
        }
        if (completionTokens == null) {
            Object content = result != null ? getFieldValue(result, "content") : null;
            completionTokens = content != null ? estimateTokenCount(content.toString()) : 0;
        }

        callLog.setPromptTokens(promptTokens);
        callLog.setCompletionTokens(completionTokens);
        callLog.setTotalTokens(promptTokens + completionTokens);
    }

    /** 简单 token 估算：中文占比高用 1.3，否则用 0.75/word */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        long zhCount = text.chars().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        if (zhCount > text.length() / 4.0) {
            return (int) Math.ceil(text.length() * ZH_TOKEN_RATIO);
        }
        return (int) Math.ceil(text.split("\\s+").length * EN_TOKEN_RATIO);
    }

    /** 从方法参数中提取 promptCode / promptVersion */
    private void extractPromptInfo(ProceedingJoinPoint point, AiModelCallLog callLog) {
        for (Object arg : point.getArgs()) {
            if (arg == null) continue;
            Object code = getFieldValue(arg, "promptCode");
            if (code != null) callLog.setPromptCode(code.toString());
            Object vars = getFieldValue(arg, "templateVars");
            if (vars instanceof Map<?, ?> map && !map.isEmpty()) {
                // 把模板变量简要记录到 extraData
                callLog.setExtraData("{\"templateVars\":" + map + "}");
            }
        }
    }

    /** 从 Shiro 获取当前用户 */
    private void fillUserInfo(AiModelCallLog callLog) {
        try {
            LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
            if (user != null) {
                callLog.setUserId(user.getId());
                callLog.setUserName(user.getUsername());
            }
        } catch (Exception ignored) {
            // 非登录上下文（如定时任务调用），忽略
        }
    }

    /** 填充请求 IP 和接口路径 */
    private void fillRequestInfo(AiModelCallLog callLog) {
        try {
            HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
            if (request != null) {
                callLog.setClientIp(IpUtils.getIpAddr(request));
                callLog.setApiPath(request.getRequestURI());
            }
        } catch (Exception ignored) {
        }
    }

    /** 反射获取对象字段值（兼容不同类名，只要字段名一致就能取到） */
    private Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    /** 安全写库，异常不外抛 */
    private void safeSave(AiModelCallLog callLog) {
        try {
            aiModelCallLogService.save(callLog);
        } catch (Exception e) {
            log.warn("保存模型调用日志失败: {}", e.getMessage());
        }
    }
}
