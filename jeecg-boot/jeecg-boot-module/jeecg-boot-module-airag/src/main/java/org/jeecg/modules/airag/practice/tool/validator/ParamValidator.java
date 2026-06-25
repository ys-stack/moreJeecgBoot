package org.jeecg.modules.airag.practice.tool.validator;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 工具参数校验工具类
 * 提供常用的参数校验方法，返回错误信息列表
 */
public class ParamValidator {
    // 危险字符正则：SQL注入、脚本注入常见片段
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("['\";`]|--|\\/\\*|\\*\\/|<script|</script", Pattern.CASE_INSENSITIVE);

    /**
     * 必填校验
     */
    public static List<String> required(String fieldName, String value) {
        List<String> errors = new ArrayList<>();
        if (value == null || value.isBlank()) {
            errors.add(fieldName + " 不能为空");
        }
        return errors;
    }
    /**
     * 长度限制
     */
    public static List<String> maxLength(String fieldName, String value, int max) {
        List<String> errors = new ArrayList<>();
        if (value != null && value.length() > max) {
            errors.add(fieldName + " 长度不能超过 " + max + " 个字符，当前 " + value.length());
        }
        return errors;
    }

    /**
     * 正则白名单 —— 只允许匹配的值通过
     * 用于 orderCode 这类有固定格式的字段
     */
    public static List<String> matchPattern(String fieldName, String value, String regex, String hint) {
        List<String> errors = new ArrayList<>();
        if (value != null && !Pattern.matches(regex, value)) {
            errors.add(fieldName + " 格式不合法：" + hint);
        }
        return errors;
    }

    /**
     * 枚举校验 —— 值必须在允许集合内
     * 用于 ticketType、priority 这类有限选项的字段
     */
    public static List<String> inEnum(String fieldName, String value, String... allowedValues) {
        List<String> errors = new ArrayList<>();
        if (value == null) return errors; // null 交给 required 管
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedValues));
        if (!allowed.contains(value)) {
            errors.add(fieldName + " 必须是以下值之一：" + String.join(", ", allowedValues) + "，当前值: " + value);
        }
        return errors;
    }

    /**
     * 危险字符检测 —— 防注入
     * 用于 keyword、description 这类自由文本字段
     */
    public static List<String> noInjection(String fieldName, String value) {
        List<String> errors = new ArrayList<>();
        if (value != null && DANGEROUS_CHARS.matcher(value).find()) {
            errors.add(fieldName + " 包含非法字符（引号、分号、注释符或脚本标签），请移除后重试");
        }
        return errors;
    }
}
