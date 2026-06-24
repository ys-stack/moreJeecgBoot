package org.jeecg.modules.airag.practice.tool.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.ToolHandler;
import org.jeecg.modules.airag.practice.user.entity.PracticeSysUser;
import org.jeecg.modules.airag.practice.user.mapper.PracticeSysUserMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
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
public class UserToolHandler implements ToolHandler {

    @Resource
    private PracticeSysUserMapper practiceSysUserMapper;

    @Override
    public String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String keyword = args.getString("keyword");

        // 参数校验
        if (keyword == null || keyword.isBlank()) {
            return "{\"error\": \"查询关键词不能为空，请提供 keyword 参数（可以是用户名、姓名、工号或手机号）\"}";
        }

        if (keyword.length() > 50) {
            return "{\"error\": \"查询keyword 参数长度过长\"}";
        }
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

        return JSON.toJSONString(users);
    }
}
