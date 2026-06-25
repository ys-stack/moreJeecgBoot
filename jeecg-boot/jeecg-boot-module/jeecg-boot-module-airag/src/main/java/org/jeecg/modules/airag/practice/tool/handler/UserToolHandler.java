package org.jeecg.modules.airag.practice.tool.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.cons.ToolCons;
import org.jeecg.modules.airag.practice.tool.validator.ParamValidator;
import org.jeecg.modules.airag.practice.user.entity.PracticeSysUser;
import org.jeecg.modules.airag.practice.user.mapper.PracticeSysUserMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询用户工具（queryUser）
 *
 * 模型决定调用此工具时，会传入 {"keyword": "张三"} 这样的 JSON。
 * 本工具根据关键词模糊匹配用户名、真实姓名、工号、手机号，返回用户信息。
 *
 * 使用轻量版 PracticeSysUser/Mapper，避免依赖 jeecg-system-biz 造成循环引用。
 *
 * 对应 ai_tool_definition 表中 tool_code='queryUser', handler_ref='userToolHandler'
 */
@Slf4j
@Component("userToolHandler")
public class UserToolHandler extends AbstractToolHandler {

    @Resource
    private PracticeSysUserMapper practiceSysUserMapper;

    @Override
    protected List<String> validate(JSONObject args) {
        List<String> errors = new ArrayList<>();
        String keyword = args.getString("keyword");

        errors.addAll(ParamValidator.required("keyword", keyword));
        if (keyword != null) {
            errors.addAll(ParamValidator.maxLength("keyword", keyword, 50));
            errors.addAll(ParamValidator.noInjection("keyword", keyword));
        }

        return errors;
    }

    @Override
    protected String execute(JSONObject args) {
        String keyword = args.getString("keyword").trim();
        log.info("[queryUser] 查询关键词: {}", keyword);
        keyword = keyword.replaceAll("%", "\\%");
        keyword = keyword.trim();
        log.info("[queryUser] 查询关键词: {}", keyword);

        // 多字段模糊查询：username / realname / work_no / phone
        QueryWrapper<PracticeSysUser> qw = new QueryWrapper<>();
        String finalKeyword = keyword;
        qw.and(w -> w
                .like("username", finalKeyword)
                .or().like("realname", finalKeyword)
                .or().like("work_no", finalKeyword)
                .or().like("phone", finalKeyword)
        );
        qw.last("LIMIT 10");

        List<PracticeSysUser> users = practiceSysUserMapper.selectList(qw);
        if (users.isEmpty()) {
            return "{\"message\": \"未找到匹配关键词 '" + keyword + "' 的用户\"}";
        }
        if (!isCurrentUserAdmin()) {
            //非管理员数据脱敏
            for (PracticeSysUser user : users) {
                user.setPhone(maskPhone(user.getPhone()));
            }
        }
        return JSON.toJSONString(users);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    @Override
    protected String getToolCode() {
        return ToolCons.tool_code_queryUser;
    }
}
